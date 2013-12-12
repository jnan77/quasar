/*
 * Quasar: lightweight threads and actors for the JVM.
 * Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.
 * 
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *  
 *   or (per the licensee's choosing)
 *  
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
package co.paralleluniverse.actors;

import co.paralleluniverse.common.reflection.ASMUtil;
import co.paralleluniverse.common.reflection.AnnotationUtil;
import co.paralleluniverse.common.reflection.ClassLoaderUtil;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 *
 * @author pron
 */
class ModuleClassLoader extends URLClassLoader {
    static {
        ClassLoader.registerAsParallelCapable();
    }
    private static final String UPGRADE_CLASSES_ATTR = "Upgrade-Classes";
    private final URL url;
    private final ClassLoader parent;
    private final Set<String> upgradeClasses;

    public ModuleClassLoader(URL jarUrl, ClassLoader parent) {
        super(new URL[]{jarUrl}, null);
        this.url = jarUrl;

        // determine upgrade classes
        try {
            JarFile jar = new JarFile(new File(jarUrl.toURI()));
            final ImmutableSet.Builder<String> builder = ImmutableSet.builder();

            Manifest manifest = jar.getManifest();
            Attributes attributes = manifest.getMainAttributes();
            String ucstr = attributes.getValue(UPGRADE_CLASSES_ATTR);
            if (ucstr != null && !ucstr.trim().isEmpty()) {
                if (ucstr.trim().equals("*")) {
                    ClassLoaderUtil.accept(this, new ClassLoaderUtil.Visitor() {
                        @Override
                        public void visit(String resource, URL url, ClassLoader cl) {
                            if (!ClassLoaderUtil.isClassfile(resource))
                                return;
                            final String className = ClassLoaderUtil.resourceToClass(resource);
                            if (ASMUtil.isAssignableFrom(Actor.class, className, ModuleClassLoader.this))
                                builder.add(className);
                        }
                    });
                } else {
                    for (String className : ucstr.split("\\s"))
                        builder.add(className);
                }
            }

            ClassLoaderUtil.accept(this, new ClassLoaderUtil.Visitor() {
                @Override
                public void visit(String resource, URL url, ClassLoader cl) {
                    if (!ClassLoaderUtil.isClassfile(resource))
                        return;
                    final String className = ClassLoaderUtil.resourceToClass(resource);
                    try (InputStream is = cl.getResourceAsStream(resource)) {
                        if (AnnotationUtil.hasClassAnnotation(Upgrade.class, is))
                            builder.add(className);
                    } catch (IOException e) {
                        throw new RuntimeException("Exception while scanning class " + className + " for Upgrade annotation", e);
                    }
                }
            });

            this.upgradeClasses = builder.build();
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }

        this.parent = parent; // must be done last, after scanning
    }

    public URL getURL() {
        return url;
    }

    public Set<String> getUpgradeClasses() {
        return upgradeClasses;
    }

    public Class<?> findClassInModule(String name) throws ClassNotFoundException {
        Class<?> loaded = super.findLoadedClass(name);
        if (loaded != null)
            return loaded;
        return super.findClass(name); // first try to use the URLClassLoader findClass
    }

    public Class<?> findLoadedClassInModule(String name) {
        return super.findLoadedClass(name);
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        Class<?> loaded = super.findLoadedClass(name);
        if (loaded != null)
            return loaded;
        try {
            return super.findClass(name); // first try to use the URLClassLoader findClass
        } catch (ClassNotFoundException e) {
            if (parent != null)
                return parent.loadClass(name);
            throw e;
        }
    }

    @Override
    public URL getResource(String name) {
        URL url = super.getResource(name);
        if (url == null && parent != null)
            url = parent.getResource(name);
        return url;
    }

    @Override
    public String toString() {
        return "ModuleClassLoader{" + "url=" + url + '}';
    }
}
