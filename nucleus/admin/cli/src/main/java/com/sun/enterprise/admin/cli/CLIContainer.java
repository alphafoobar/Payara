/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package com.sun.enterprise.admin.cli;

import com.sun.enterprise.admin.remote.Metrix;
import com.sun.enterprise.module.ModulesRegistry;
import com.sun.enterprise.module.single.StaticModulesRegistry;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import javax.inject.Inject;
import org.glassfish.hk2.api.MultiException;
import org.glassfish.hk2.api.PerLookup;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.utilities.DescriptorImpl;
import org.glassfish.hk2.utilities.ServiceLocatorUtilities;
import org.jvnet.hk2.config.InjectionManager;
import org.jvnet.hk2.config.InjectionResolver;

/** This exists mainly due performance reason.
 * After construct it starts hk2 descriptors parsing because is is significantly
 * more effective then HK2 initialization. <br/>
 * It retrieve list of CLICommands and use it to determine if requested command
 * is local or remote. <br/>
 * For local CLICommand which injects just ProgramOptions or Environment it can 
 * also create requested instance. For other command uses lazy loaded HK2 
 * ServiceLocator.
 *
 * @author martinmares
 */
public class CLIContainer {
    
    class SimpleInjectionResolver extends InjectionResolver<Inject> {

        public SimpleInjectionResolver(Class<Inject> type) {
            super(type);
        }

        @Override
        public <V> V getValue(Object o, AnnotatedElement ae, Type genricType, Class<V> type) throws MultiException {
            if (type.isAssignableFrom(ProgramOptions.class)) {
                return (V) getProgramOptions();
            }
            if (type.isAssignableFrom(Environment.class)) {
                return (V) getEnvironment();
            }
            if (type.isAssignableFrom(CLIContainer.class)) {
                return (V) CLIContainer.this;
            }
            throw new IllegalStateException();
        }
        
    }
    
    private static final InjectionManager injectionMgr = new InjectionManager();
    
    protected final ClassLoader classLoader;
    protected final Logger logger;
    
    protected ServiceLocator serviceLocator;
    private ProgramOptions programOptions;
    private Environment environment;
    
    
    private Map<String, String> cliCommandsNames;
    private final CountDownLatch initLatch = new CountDownLatch(1);

    public CLIContainer(final ClassLoader classLoader, final Logger logger) {
        this.classLoader = classLoader;
        this.logger = logger;
        final Thread th = new Thread (new Runnable() {
                @Override
                public void run() {
                    try {
                        cliCommandsNames = parseHk2Locators();
                    } catch (IOException ex) {
                        logger.log(Level.FINER, "Can't fast parse hk2 locators! HK2 ServiceLocator must be used");
                    } finally {
                        initLatch.countDown();
                    }
                }
            });
        th.setDaemon(true);
        th.start();
     }
    
    private URL getUrlOfOwnJar() throws IOException {
        URL resource = classLoader.getResource(CLIContainer.class.getName().replace('.', '/') + ".class");
        if (resource != null) {
            String str = resource.toString();
            int ind = str.indexOf('!');
            if (ind >= 0) {
                if (str.startsWith("jar:")) {
                    str = str.substring(4, ind);
                } else {
                    str = str.substring(0, ind);
                }
            }
            return new URL(str);
        } else {
            return null;
        }
    }
    
    /** Returns URL to admin-cli.jar and all jars in extension directory. 
     */
    private Set<URL> getMinimalSetOfJars() throws IOException {
        Set<URL> result;
        if (classLoader instanceof DirectoryClassLoader) {
            //Our own classloader for extra directory
            DirectoryClassLoader dcl = (DirectoryClassLoader) classLoader;
            URL[] urls = dcl.getURLs();
            result = new HashSet<URL>(urls.length + 1);
            result.addAll(Arrays.asList(urls));
        } else {
            result = new HashSet<URL>(1);
        }
        URL url = getUrlOfOwnJar();
        if (url != null) {
            result.add(url);
        }
        return result;
    }
    
    private Object createInstance(String name) 
            throws ClassNotFoundException, InstantiationException, IllegalAccessException, IllegalStateException {
        if (name == null) {
            return null;
        }
        Class<?> clazz = Class.forName(name);
        if (clazz.getAnnotation(PerLookup.class) == null) {
            //Other scopes => HK2
            return null;
        }
        Object result = clazz.newInstance();
        InjectionResolver<Inject> injector = new SimpleInjectionResolver(Inject.class);
        injectionMgr.inject(result, injector);
        return result;
    }
    
    private void parseInHk2LocatorOrig(BufferedReader reader, Map<String, String> cliCommandNames) throws IOException {
        DescriptorImpl desc = new DescriptorImpl();
        while (desc.readObject(reader)) {
            if (desc.getAdvertisedContracts().contains(CLICommand.class.getName())) {
                cliCommandNames.put(desc.getName(), desc.getImplementation());
            }
        }
    }
    
    private Map<String, String> parseHk2Locators() throws IOException {
        Map<String, String> result = new HashMap<String, String>();
        Set<URL> minimalSetOfJars = getMinimalSetOfJars();
        for (URL url : minimalSetOfJars) {
            String filename = url.getFile();
            JarFile jar = new JarFile(new File(filename));
            ZipEntry entry = jar.getEntry("META-INF/hk2-locator/default");
            if (entry != null) {
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new InputStreamReader(jar.getInputStream(entry)));
                    parseInHk2LocatorOrig(reader, result);
                } finally {
                    try { reader.close(); } catch (Exception ex) {
                    }
                }
            } else {
            }
        }
        return result;
    }
    
    private String getCommandClassName(String name) throws InterruptedException, IllegalStateException {
        initLatch.await();
        if (cliCommandsNames == null) {
            throw new IllegalStateException();
        } else {
            return cliCommandsNames.get(name);
        }
    }
    
    public ServiceLocator getServiceLocator() {
        if (serviceLocator == null) {
            Metrix.event("Init hk2 - start");
            ModulesRegistry registry = new StaticModulesRegistry(this.classLoader);
            serviceLocator = registry.createServiceLocator("default");
            if (programOptions != null) {
                ServiceLocatorUtilities.addOneConstant(serviceLocator, programOptions);
            }
            if (environment != null) {
                ServiceLocatorUtilities.addOneConstant(serviceLocator, environment);
            }
            ServiceLocatorUtilities.addOneConstant(serviceLocator, this);
            Metrix.event("Init hk2 - done");
        }
        return serviceLocator;
    }
    
    public CLICommand getLocalCommand(String name) {
        if (serviceLocator == null) {
            //First hard chack if it is local command
            try {
                String className = getCommandClassName(name);
                if (className == null) {
                    if (logger.isLoggable(Level.FINER)) {
                        logger.log(Level.FINER, "CLICommand not found for name {0}", name);
                    }
                    return null;
                }
                CLICommand result = (CLICommand) createInstance(className);
                if (result != null) {
                    if (logger.isLoggable(Level.FINER)) {
                        logger.log(Level.FINER, "CLIContainer creates instance for command {0}", name);
                    }
                    return result;
                }
            } catch (Exception ex) {
                //Not special case. 
            }
        }
        if (logger.isLoggable(Level.FINER)) {
            logger.log(Level.FINER, "HK2 Service locator will be used for command {0}", name);
        }
        return getServiceLocator().getService(CLICommand.class, name);
    }
    
    public void setProgramOptions(ProgramOptions programOptions) {
        this.programOptions = programOptions;
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public ProgramOptions getProgramOptions() {
        return programOptions;
    }

    public Environment getEnvironment() {
        return environment;
    }
    
}