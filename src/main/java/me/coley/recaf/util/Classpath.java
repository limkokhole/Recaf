package me.coley.recaf.util;

import com.google.common.reflect.ClassPath;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import me.coley.recaf.Logging;

public class Classpath {
	/**
	 * The system classloader, provided by {@link ClassLoader#getSystemClassLoader()}.
	 */
	public static final ClassLoader scl = ClassLoader.getSystemClassLoader();

	/**
	 * The system classpath.
	 */
	public static final ClassPath cp;

	/**
	 * A sorted, unmodifiable list of all class names in {@linkplain #cp the system classpath}.
	 */
	private static final List<String> systemClassNames;

	static {
		ClassPathScanner scanner = new ClassPathScanner();
		scanner.scan(scl);
		cp = scanner.classPath;
		systemClassNames = Collections.unmodifiableList(scanner.internalNames);
	}

	/**
	 * Returns a sorted, unmodifiable list of all class names in the system classpath.
	 */
	public static List<String> getSystemClassNames() {
		return systemClassNames;
	}

	/**
	 * Checks if bootstrap classes is found in {@link #getSystemClassNames()}.
	 * @return {@code true} if they do, {@code false} if they don't
	 */
	public static boolean isBootstrapClassesFound() {
		return checkBootstrapClassExists(getSystemClassNames());
	}

	/**
	 * Returns the class associated with the specified name, using {@linkplain #scl the system class loader}.
	 *
	 * <p> The class will not be initialized if it has not been initialized earlier.
	 * <p> This is equivalent to {@code Class.forName(className, false, ClassLoader.getSystemClassLoader())}
	 *
	 * @return class object representing the desired class
	 * @throws ClassNotFoundException if the class cannot be located by the system class loader
	 * @see Class#forName(String, boolean, ClassLoader)
	 */
	public static Class<?> getSystemClass(String className) throws ClassNotFoundException {
		return Class.forName(className, false, Classpath.scl);
	}

	/**
	 * Returns the class associated with the specified name, using {@linkplain #scl the system class loader}.
	 *
	 * <p> The class will not be initialized if it has not been initialized earlier.
	 * <p> This is equivalent to {@code Class.forName(className, false, ClassLoader.getSystemClassLoader())}
	 *
	 * @return class object representing the desired class,
	 *         or {@code null} if it cannot be located by the system class loader
	 * @see Class#forName(String, boolean, ClassLoader)
	 */
	public static Optional<Class<?>> getSystemClassIfExists(String className) {
		try {
			return Optional.of(getSystemClass(className));
		} catch (ClassNotFoundException | NullPointerException ex) {
			return Optional.empty();
		}
	}

	/**
	 * Internal utility to check if bootstrap classes exist in a list of class names.
	 */
	private static boolean checkBootstrapClassExists(Collection<String> names) {
		String name = Object.class.getName();
		return names.contains(name) || names.contains(name.replace('.', '/'));
	}

	/**
	 * Utility class for easy state management.
	 */
	private static class ClassPathScanner {
		public ClassPath classPath;
		public List<String> names;
		public List<String> internalNames;

		private void updateClassPath(ClassLoader loader) {
			try {
				classPath = ClassPath.from(loader);
				names = classPath.getResources().stream()
						.filter(ClassPath.ClassInfo.class::isInstance)
						.map(ClassPath.ClassInfo.class::cast)
						.map(ClassPath.ClassInfo::getName)
						.collect(Collectors.toCollection(ArrayList::new));
			} catch (IOException e) {
				throw new UncheckedIOException("Unable to scan classpath entries: " +
						loader.getClass().getName(), e);
			}
		}

		private boolean checkBootstrapClass() {
			return checkBootstrapClassExists(names);
		}

		public void scan(ClassLoader classLoader) {
			updateClassPath(classLoader);

			// In some JVM implementation, the bootstrap class loader is implemented directly in native code
			// and does not exist as a ClassLoader instance. Unfortunately, Oracle JVM is one of them.
			//
			// Considering Guava's ClassPath util works (and can only work) by scanning urls from an URLClassLoader,
			// this means it cannot find any of the standard API like java.lang.Object
			// without explicitly specifying `-classpath=rt.jar` etc. in the launch arguments
			// (only IDEs' Run/Debug seems to do that automatically)
			//
			// It further means that in most of the circumstances (including `java -jar recaf.jar`)
			// auto-completion will not able to suggest internal names of any of the classes under java.*,
			// which will largely reduce the effectiveness of the feature.
			if (!checkBootstrapClass()) {
				float vmVersion = Float.parseFloat(System.getProperty("java.class.version")) - 44;
				if (vmVersion < 9) {
					try {
						Method method = ClassLoader.class.getDeclaredMethod("getBootstrapClassPath");
						method.setAccessible(true);
						Field field = URLClassLoader.class.getDeclaredField("ucp");
						field.setAccessible(true);

						Object bootstrapClasspath = method.invoke(null);
						scanBootstrapClasspath(field, classLoader, bootstrapClasspath);
					} catch (ReflectiveOperationException | SecurityException e) {
						throw new ExceptionInInitializerError(e);
					}
				} else {
					try {

						// Before we will do that, break into Jigsaw module system to grant full access
						Class<?> module = Class.forName("java.lang.Module");
						Class<?> layer = Class.forName("java.lang.ModuleLayer");
						Field lookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
						lookupField.setAccessible(true);
						MethodHandles.Lookup lookup = (MethodHandles.Lookup) lookupField.get(null);
						MethodHandle export = lookup
								.findVirtual(module, "implAddOpens", MethodType.methodType(void.class, String.class));
						MethodHandle getPackages = lookup
								.findVirtual(module, "getPackages", MethodType.methodType(Set.class));
						MethodHandle getModule = lookup
								.findVirtual(Class.class, "getModule", MethodType.methodType(module));
						MethodHandle getLayer = lookup
								.findVirtual(module, "getLayer", MethodType.methodType(layer));
						MethodHandle layerModules = lookup
								.findVirtual(layer, "modules", MethodType.methodType(Set.class));
						MethodHandle unnamedModule = lookup
								.findVirtual(ClassLoader.class, "getUnnamedModule", MethodType.methodType(module));
						Set modules = new HashSet();

						Object ourModule = getModule.invoke(Classpath.class);
						Object ourLayer = getLayer.invoke(ourModule);
						if (ourLayer != null) {
							modules.addAll((Set) layerModules.invoke(ourLayer));
						}
						modules.addAll(
								(Set) layerModules
										.invoke(lookup.findStatic(layer, "boot", MethodType.methodType(layer))
												.invoke()));
						for (ClassLoader c = Classpath.class.getClassLoader(); c != null; c = c.getParent()) {
							modules.add(unnamedModule.invoke(c));
						}

						for (Object impl : modules) {
							for (String name : (Set<String>) getPackages.invoke(impl)) {
								export.invoke(impl, name);
							}
						}
						MethodHandle mapHandle = lookup.findStaticGetter(Class.forName("jdk.internal.reflect.Reflection"), "fieldFilterMap", Map.class);
						Map<Class<?>, Set<String>> fieldFilterMap = (Map<Class<?>, Set<String>>) mapHandle.invokeExact();
						fieldFilterMap.remove(Field.class);

						Method method = Class.forName("jdk.internal.loader.ClassLoaders").getDeclaredMethod("bootLoader");
						method.setAccessible(true);
						Object bootLoader = method.invoke(null);
						Field field = bootLoader.getClass().getSuperclass().getDeclaredField("ucp");
						field.setAccessible(true);

						Object bootstrapClasspath = field.get(bootLoader);
						scanBootstrapClasspath(URLClassLoader.class.getDeclaredField("ucp"), classLoader, bootstrapClasspath);
					} catch (Throwable t) {
						throw new ExceptionInInitializerError(t);
					}
				}
			}

			// Map to internal names
			internalNames = names.stream()
					.map(name -> name.replace('.', '/'))
					.sorted(Comparator.naturalOrder())
					.collect(Collectors.toCollection(ArrayList::new));
			((ArrayList<String>) internalNames).trimToSize();
		}

		private void scanBootstrapClasspath(Field field, ClassLoader classLoader, Object bootstrapClasspath) throws IllegalAccessException, NoSuchFieldException {
			URLClassLoader dummyLoader = new URLClassLoader(new URL[0], classLoader);
			if (Modifier.isFinal(field.getModifiers())) {
				Field modifiers = Field.class.getDeclaredField("modifiers");
				modifiers.setAccessible(true);
				modifiers.setInt(field, field.getModifiers() & ~Modifier.FINAL);
			}
			// Change the URLClassPath in the dummy loader to the bootstrap one.
			field.set(dummyLoader, bootstrapClasspath);
			// And then feed it into Guava's ClassPath scanner.
			updateClassPath(dummyLoader);

			if (!checkBootstrapClass()) {
				Logging.warn("Bootstrap classes are (still) missing from the classpath scan!");
			}
		}
	}
}
