/*
 * Copyright (c) 2016, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.serviceprovider;

import static java.lang.Thread.currentThread;
import static jdk.vm.ci.services.Services.IS_BUILDING_NATIVE_IMAGE;
import static jdk.vm.ci.services.Services.IS_IN_NATIVE_IMAGE;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicLong;

import jdk.vm.ci.code.VirtualObject;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.SpeculationLog.SpeculationReason;
import jdk.vm.ci.runtime.JVMCI;
import jdk.vm.ci.services.JVMCIPermission;
import jdk.vm.ci.services.Services;

/**
 * LabsJDK 11 version of {@link GraalServices}.
 */
public final class GraalServices {

    private static final Map<Class<?>, List<?>> servicesCache = IS_BUILDING_NATIVE_IMAGE ? new HashMap<>() : null;

    private static final Constructor<? extends SpeculationReason> encodedSpeculationReasonConstructor;

    static {
        Constructor<? extends SpeculationReason> constructor = null;
        try {
            @SuppressWarnings("unchecked")
            Class<? extends SpeculationReason> theClass = (Class<? extends SpeculationReason>) Class.forName("jdk.vm.ci.meta.EncodedSpeculationReason");
            constructor = theClass.getDeclaredConstructor(Integer.TYPE, String.class, Object[].class);
        } catch (ClassNotFoundException e) {
        } catch (NoSuchMethodException e) {
            throw new InternalError("EncodedSpeculationReason exists but constructor is missing", e);
        }
        encodedSpeculationReasonConstructor = constructor;
    }

    private GraalServices() {
    }

    /**
     * Gets an {@link Iterable} of the providers available for a given service.
     *
     * @throws SecurityException if on JDK8 and a security manager is present and it denies
     *             {@link JVMCIPermission}
     */
    @SuppressWarnings("unchecked")
    public static <S> Iterable<S> load(Class<S> service) {
        if (IS_IN_NATIVE_IMAGE || IS_BUILDING_NATIVE_IMAGE) {
            List<?> list = servicesCache.get(service);
            if (list != null) {
                return (Iterable<S>) list;
            }
            if (IS_IN_NATIVE_IMAGE) {
                throw new InternalError(String.format("No %s providers found when building native image", service.getName()));
            }
        }

        Iterable<S> providers = load0(service);

        if (IS_BUILDING_NATIVE_IMAGE) {
            synchronized (servicesCache) {
                ArrayList<S> providersList = new ArrayList<>();
                for (S provider : providers) {
                    Module module = provider.getClass().getModule();
                    if (isHotSpotGraalModule(module.getName())) {
                        providersList.add(provider);
                    }
                }
                providers = providersList;
                servicesCache.put(service, providersList);
                return providers;
            }
        }

        return providers;
    }

    /**
     * Determines if the module named by {@code name} is part of Graal when it is configured as a
     * HotSpot JIT compiler.
     */
    private static boolean isHotSpotGraalModule(String name) {
        if (name != null) {
            return name.equals("jdk.internal.vm.compiler") ||
                            name.equals("jdk.internal.vm.compiler.management") ||
                            name.equals("com.oracle.graal.graal_enterprise");
        }
        return false;
    }

    protected static <S> Iterable<S> load0(Class<S> service) {
        Module module = GraalServices.class.getModule();
        // Graal cannot know all the services used by another module
        // (e.g. enterprise) so dynamically register the service use now.
        if (!module.canUse(service)) {
            module.addUses(service);
        }

        ModuleLayer layer = module.getLayer();
        Iterable<S> iterable = ServiceLoader.load(layer, service);
        return new Iterable<>() {
            @Override
            public Iterator<S> iterator() {
                Iterator<S> iterator = iterable.iterator();
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return iterator.hasNext();
                    }

                    @Override
                    public S next() {
                        S provider = iterator.next();
                        // Allow Graal extensions to access JVMCI
                        openJVMCITo(provider.getClass());
                        return provider;
                    }

                    @Override
                    public void remove() {
                        iterator.remove();
                    }
                };
            }
        };
    }

    /**
     * Opens all JVMCI packages to the module of a given class. This relies on JVMCI already having
     * opened all its packages to the module defining {@link GraalServices}.
     *
     * @param other all JVMCI packages will be opened to the module defining this class
     */
    static void openJVMCITo(Class<?> other) {
        if (IS_IN_NATIVE_IMAGE) {
            return;
        }

        Module jvmciModule = JVMCI_MODULE;
        Module otherModule = other.getModule();
        if (jvmciModule != otherModule) {
            for (String pkg : jvmciModule.getPackages()) {
                if (!jvmciModule.isOpen(pkg, otherModule)) {
                    // JVMCI initialization opens all JVMCI packages
                    // to Graal which is a prerequisite for Graal to
                    // open JVMCI packages to other modules.
                    JVMCI.getRuntime();

                    jvmciModule.addOpens(pkg, otherModule);
                }
            }
        }
    }

    /**
     * Gets the provider for a given service for which at most one provider must be available.
     *
     * @param service the service whose provider is being requested
     * @param required specifies if an {@link InternalError} should be thrown if no provider of
     *            {@code service} is available
     * @return the requested provider if available else {@code null}
     * @throws SecurityException if on JDK8 and a security manager is present and it denies
     *             {@link JVMCIPermission}
     */
    public static <S> S loadSingle(Class<S> service, boolean required) {
        assert !service.getName().startsWith("jdk.vm.ci") : "JVMCI services must be loaded via " + Services.class.getName();
        Iterable<S> providers = load(service);
        S singleProvider = null;
        try {
            for (Iterator<S> it = providers.iterator(); it.hasNext();) {
                singleProvider = it.next();
                if (it.hasNext()) {
                    S other = it.next();
                    throw new InternalError(String.format("Multiple %s providers found: %s, %s", service.getName(), singleProvider.getClass().getName(), other.getClass().getName()));
                }
            }
        } catch (ServiceConfigurationError e) {
            // If the service is required we will bail out below.
        }
        if (singleProvider == null) {
            if (required) {
                throw new InternalError(String.format("No provider for %s found", service.getName()));
            }
        }
        return singleProvider;
    }

    /**
     * Gets the class file bytes for {@code c}.
     */
    public static InputStream getClassfileAsStream(Class<?> c) throws IOException {
        String classfilePath = c.getName().replace('.', '/') + ".class";
        return c.getModule().getResourceAsStream(classfilePath);
    }

    private static final Module JVMCI_MODULE = Services.class.getModule();

    /**
     * A JVMCI package dynamically exported to trusted modules.
     */
    private static final String JVMCI_RUNTIME_PACKAGE = "jdk.vm.ci.runtime";
    static {
        assert JVMCI_MODULE.getPackages().contains(JVMCI_RUNTIME_PACKAGE);
    }

    /**
     * Determines if invoking {@link Object#toString()} on an instance of {@code c} will only run
     * trusted code.
     */
    public static boolean isToStringTrusted(Class<?> c) {
        if (IS_IN_NATIVE_IMAGE) {
            return true;
        }

        Module module = c.getModule();
        Module jvmciModule = JVMCI_MODULE;
        if (module == jvmciModule || jvmciModule.isOpen(JVMCI_RUNTIME_PACKAGE, module)) {
            // Can access non-statically-exported package in JVMCI
            return true;
        }
        return false;
    }

    static SpeculationReason createSpeculationReason(int groupId, String groupName, Object... context) {
        if (encodedSpeculationReasonConstructor != null) {
            SpeculationEncodingAdapter adapter = new SpeculationEncodingAdapter();
            try {
                Object[] flattened = adapter.flatten(context);
                return encodedSpeculationReasonConstructor.newInstance(groupId, groupName, flattened);
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return new UnencodedSpeculationReason(groupId, groupName, context);
    }

    /**
     * Gets a unique identifier for this execution such as a process ID or a
     * {@linkplain #getGlobalTimeStamp() fixed timestamp}.
     */
    public static String getExecutionID() {
        return Long.toString(ProcessHandle.current().pid());
    }

    private static final AtomicLong globalTimeStamp = new AtomicLong();

    /**
     * Gets a time stamp for the current process. This method will always return the same value for
     * the current VM execution.
     */
    public static long getGlobalTimeStamp() {
        if (globalTimeStamp.get() == 0) {
            globalTimeStamp.compareAndSet(0, System.currentTimeMillis());
        }
        return globalTimeStamp.get();
    }

    /**
     * Returns an approximation of the total amount of memory, in bytes, allocated in heap memory
     * for the thread of the specified ID. The returned value is an approximation because some Java
     * virtual machine implementations may use object allocation mechanisms that result in a delay
     * between the time an object is allocated and the time its size is recorded.
     * <p>
     * If the thread of the specified ID is not alive or does not exist, this method returns
     * {@code -1}. If thread memory allocation measurement is disabled, this method returns
     * {@code -1}. A thread is alive if it has been started and has not yet died.
     * <p>
     * If thread memory allocation measurement is enabled after the thread has started, the Java
     * virtual machine implementation may choose any time up to and including the time that the
     * capability is enabled as the point where thread memory allocation measurement starts.
     *
     * @param id the thread ID of a thread
     * @return an approximation of the total memory allocated, in bytes, in heap memory for a thread
     *         of the specified ID if the thread of the specified ID exists, the thread is alive,
     *         and thread memory allocation measurement is enabled; {@code -1} otherwise.
     *
     * @throws IllegalArgumentException if {@code id} {@code <=} {@code 0}.
     * @throws UnsupportedOperationException if the Java virtual machine implementation does not
     *             {@linkplain #isThreadAllocatedMemorySupported() support} thread memory allocation
     *             measurement.
     */
    public static long getThreadAllocatedBytes(long id) {
        JMXService jmx = JMXService.instance;
        if (jmx == null) {
            throw new UnsupportedOperationException();
        }
        return jmx.getThreadAllocatedBytes(id);
    }

    /**
     * Convenience method for calling {@link #getThreadAllocatedBytes(long)} with the id of the
     * current thread.
     */
    public static long getCurrentThreadAllocatedBytes() {
        return getThreadAllocatedBytes(currentThread().getId());
    }

    /**
     * Returns the total CPU time for the current thread in nanoseconds. The returned value is of
     * nanoseconds precision but not necessarily nanoseconds accuracy. If the implementation
     * distinguishes between user mode time and system mode time, the returned CPU time is the
     * amount of time that the current thread has executed in user mode or system mode.
     *
     * @return the total CPU time for the current thread if CPU time measurement is enabled;
     *         {@code -1} otherwise.
     *
     * @throws UnsupportedOperationException if the Java virtual machine does not
     *             {@linkplain #isCurrentThreadCpuTimeSupported() support} CPU time measurement for
     *             the current thread
     */
    public static long getCurrentThreadCpuTime() {
        JMXService jmx = JMXService.instance;
        if (jmx == null) {
            throw new UnsupportedOperationException();
        }
        return jmx.getCurrentThreadCpuTime();
    }

    /**
     * Determines if the Java virtual machine implementation supports thread memory allocation
     * measurement.
     */
    public static boolean isThreadAllocatedMemorySupported() {
        JMXService jmx = JMXService.instance;
        if (jmx == null) {
            return false;
        }
        return jmx.isThreadAllocatedMemorySupported();
    }

    /**
     * Determines if the Java virtual machine supports CPU time measurement for the current thread.
     */
    public static boolean isCurrentThreadCpuTimeSupported() {
        JMXService jmx = JMXService.instance;
        if (jmx == null) {
            return false;
        }
        return jmx.isCurrentThreadCpuTimeSupported();
    }

    /**
     * Gets the input arguments passed to the Java virtual machine which does not include the
     * arguments to the {@code main} method. This method returns an empty list if there is no input
     * argument to the Java virtual machine.
     * <p>
     * Some Java virtual machine implementations may take input arguments from multiple different
     * sources: for examples, arguments passed from the application that launches the Java virtual
     * machine such as the 'java' command, environment variables, configuration files, etc.
     * <p>
     * Typically, not all command-line options to the 'java' command are passed to the Java virtual
     * machine. Thus, the returned input arguments may not include all command-line options.
     *
     * @return the input arguments to the JVM or {@code null} if they are unavailable
     */
    public static List<String> getInputArguments() {
        JMXService jmx = JMXService.instance;
        if (jmx == null) {
            return null;
        }
        return jmx.getInputArguments();
    }

    /**
     * Returns the fused multiply add of the three arguments; that is, returns the exact product of
     * the first two arguments summed with the third argument and then rounded once to the nearest
     * {@code float}.
     */
    public static float fma(float a, float b, float c) {
        return Math.fma(a, b, c);
    }

    /**
     * Returns the fused multiply add of the three arguments; that is, returns the exact product of
     * the first two arguments summed with the third argument and then rounded once to the nearest
     * {@code double}.
     */
    public static double fma(double a, double b, double c) {
        return Math.fma(a, b, c);
    }

    static final Method virtualObjectGetMethod;

    static {
        Method virtualObjectGet = null;
        try {
            virtualObjectGet = VirtualObject.class.getDeclaredMethod("get", ResolvedJavaType.class, Integer.TYPE, Boolean.TYPE);
        } catch (Exception e) {
            // VirtualObject.get that understands autobox isn't available
        }
        virtualObjectGetMethod = virtualObjectGet;
    }

    public static VirtualObject createVirtualObject(ResolvedJavaType type, int id, boolean isAutoBox) {
        if (virtualObjectGetMethod != null) {
            try {
                return (VirtualObject) virtualObjectGetMethod.invoke(null, type, id, isAutoBox);
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return VirtualObject.get(type, id);
    }

    public static int getJavaUpdateVersion() {
        return Runtime.version().update();
    }
}
