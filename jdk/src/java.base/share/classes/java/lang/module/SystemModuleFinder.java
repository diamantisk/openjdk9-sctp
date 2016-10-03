/*
 * Copyright (c) 2015, 2016, Oracle and/or its affiliates. All rights reserved.
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

package java.lang.module;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import jdk.internal.jimage.ImageLocation;
import jdk.internal.jimage.ImageReader;
import jdk.internal.jimage.ImageReaderFactory;
import jdk.internal.module.ModuleHashes;
import jdk.internal.module.ModuleHashes.HashSupplier;
import jdk.internal.module.SystemModules;
import jdk.internal.module.ModulePatcher;
import jdk.internal.perf.PerfCounter;

/**
 * A {@code ModuleFinder} that finds modules that are linked into the
 * run-time image.
 *
 * The modules linked into the run-time image are assumed to have the
 * ConcealedPackages attribute.
 */

class SystemModuleFinder implements ModuleFinder {

    private static final PerfCounter initTime
        = PerfCounter.newPerfCounter("jdk.module.finder.jimage.initTime");
    private static final PerfCounter moduleCount
        = PerfCounter.newPerfCounter("jdk.module.finder.jimage.modules");
    private static final PerfCounter packageCount
        = PerfCounter.newPerfCounter("jdk.module.finder.jimage.packages");
    private static final PerfCounter exportsCount
        = PerfCounter.newPerfCounter("jdk.module.finder.jimage.exports");
    // ImageReader used to access all modules in the image
    private static final ImageReader imageReader;

    // the set of modules in the run-time image
    private static final Set<ModuleReference> modules;

    // maps module name to module reference
    private static final Map<String, ModuleReference> nameToModule;

    /**
     * For now, the module references are created eagerly on the assumption
     * that service binding will require all modules to be located.
     */
    static {
        long t0 = System.nanoTime();
        imageReader = ImageReaderFactory.getImageReader();

        String[] names = moduleNames();
        ModuleDescriptor[] descriptors = descriptors(names);

        int n = names.length;
        moduleCount.add(n);

        Set<ModuleReference> mods = new HashSet<>(n);
        Map<String, ModuleReference> map = new HashMap<>(n);

        for (int i = 0; i < n; i++) {
            ModuleDescriptor md = descriptors[i];

            // create the ModuleReference
            ModuleReference mref = toModuleReference(md, hashSupplier(i, names[i]));

            mods.add(mref);
            map.put(names[i], mref);

            // counters
            packageCount.add(md.packages().size());
            exportsCount.add(md.exports().size());
        }

        modules = Collections.unmodifiableSet(mods);
        nameToModule = map;

        initTime.addElapsedTimeFrom(t0);
    }

    /*
     * Returns an array of ModuleDescriptor of the given module names.
     *
     * This obtains ModuleDescriptors from SystemModules class that is generated
     * from the jlink system-modules plugin.  ModuleDescriptors have already
     * been validated at link time.
     *
     * If java.base is patched, or fastpath is disabled for troubleshooting
     * purpose, it will fall back to find system modules via jrt file system.
     */
    private static ModuleDescriptor[] descriptors(String[] names) {
        // fastpath is enabled by default.
        // It can be disabled for troubleshooting purpose.
        boolean disabled =
            System.getProperty("jdk.system.module.finder.disabledFastPath") != null;

        // fast loading of ModuleDescriptor of system modules
        if (isFastPathSupported() && !disabled)
            return SystemModules.modules();

        // if fast loading of ModuleDescriptors is disabled
        // fallback to read module-info.class
        ModuleDescriptor[] descriptors = new ModuleDescriptor[names.length];
        for (int i = 0; i < names.length; i++) {
            String mn = names[i];
            ImageLocation loc = imageReader.findLocation(mn, "module-info.class");
            descriptors[i] = ModuleDescriptor.read(imageReader.getResourceBuffer(loc));

            // add the recorded hashes of tied modules
            Hashes.add(descriptors[i]);
        }
        return descriptors;
    }

    private static boolean isFastPathSupported() {
       return SystemModules.MODULE_NAMES.length > 0;
    }

    private static String[] moduleNames() {
        if (isFastPathSupported())
            // module names recorded at link time
            return SystemModules.MODULE_NAMES;

        // this happens when java.base is patched with java.base
        // from an exploded image
        return imageReader.getModuleNames();
    }

    private static ModuleReference toModuleReference(ModuleDescriptor md,
                                                     HashSupplier hash)
    {
        String mn = md.name();
        URI uri = URI.create("jrt:/" + mn);

        Supplier<ModuleReader> readerSupplier = new Supplier<>() {
            @Override
            public ModuleReader get() {
                return new ImageModuleReader(mn, uri);
            }
        };

        ModuleReference mref =
            new ModuleReference(md, uri, readerSupplier, hash);

        // may need a reference to a patched module if --patch-module specified
        mref = ModulePatcher.interposeIfNeeded(mref);

        return mref;
    }

    private static HashSupplier hashSupplier(int index, String name) {
        if (isFastPathSupported()) {
            return new HashSupplier() {
                @Override
                public String generate(String algorithm) {
                    return SystemModules.MODULES_TO_HASH[index];
                }
            };
        } else {
            return Hashes.hashFor(name);
        }
    }

    /*
     * This helper class is only used when SystemModules is patched.
     * It will get the recorded hashes from module-info.class.
     */
    private static class Hashes {
        static Map<String, String> hashes = new HashMap<>();

        static void add(ModuleDescriptor descriptor) {
            Optional<ModuleHashes> ohashes = descriptor.hashes();
            if (ohashes.isPresent()) {
                hashes.putAll(ohashes.get().hashes());
            }
        }

        static HashSupplier hashFor(String name) {
            if (!hashes.containsKey(name))
                return null;

            return new HashSupplier() {
                @Override
                public String generate(String algorithm) {
                    return hashes.get(name);
                }
            };
        }
    }

    SystemModuleFinder() { }

    @Override
    public Optional<ModuleReference> find(String name) {
        Objects.requireNonNull(name);
        return Optional.ofNullable(nameToModule.get(name));
    }

    @Override
    public Set<ModuleReference> findAll() {
        return modules;
    }


    /**
     * A ModuleReader for reading resources from a module linked into the
     * run-time image.
     */
    static class ImageModuleReader implements ModuleReader {
        private final String module;
        private volatile boolean closed;

        /**
         * If there is a security manager set then check permission to
         * connect to the run-time image.
         */
        private static void checkPermissionToConnect(URI uri) {
            SecurityManager sm = System.getSecurityManager();
            if (sm != null) {
                try {
                    URLConnection uc = uri.toURL().openConnection();
                    sm.checkPermission(uc.getPermission());
                } catch (IOException ioe) {
                    throw new UncheckedIOException(ioe);
                }
            }
        }

        ImageModuleReader(String module, URI uri) {
            checkPermissionToConnect(uri);
            this.module = module;
        }

        /**
         * Returns the ImageLocation for the given resource, {@code null}
         * if not found.
         */
        private ImageLocation findImageLocation(String name) throws IOException {
            Objects.requireNonNull(name);
            if (closed)
                throw new IOException("ModuleReader is closed");
            if (imageReader != null) {
                return imageReader.findLocation(module, name);
            } else {
                // not an images build
                return null;
            }
        }

        @Override
        public Optional<URI> find(String name) throws IOException {
            ImageLocation location = findImageLocation(name);
            if (location != null) {
                URI u = URI.create("jrt:/" + module + "/" + name);
                return Optional.of(u);
            } else {
                return Optional.empty();
            }
        }

        @Override
        public Optional<InputStream> open(String name) throws IOException {
            return read(name).map(this::toInputStream);
        }

        private InputStream toInputStream(ByteBuffer bb) { // ## -> ByteBuffer?
            try {
                int rem = bb.remaining();
                byte[] bytes = new byte[rem];
                bb.get(bytes);
                return new ByteArrayInputStream(bytes);
            } finally {
                release(bb);
            }
        }

        @Override
        public Optional<ByteBuffer> read(String name) throws IOException {
            ImageLocation location = findImageLocation(name);
            if (location != null) {
                return Optional.of(imageReader.getResourceBuffer(location));
            } else {
                return Optional.empty();
            }
        }

        @Override
        public void release(ByteBuffer bb) {
            Objects.requireNonNull(bb);
            ImageReader.releaseByteBuffer(bb);
        }

        @Override
        public void close() {
            // nothing else to do
            closed = true;
        }
    }

}
