/*
 * Copyright 2012-2017 Rafal Lewczuk <rafal.lewczuk@jitlogic.com>
 *
 * ZORKA is free software. You can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * ZORKA is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * ZORKA. If not, see <http://www.gnu.org/licenses/>.
 */

package com.jitlogic.zorka.core.test.support;

import com.jitlogic.zorka.core.spy.SpyClassTransformer;

import static org.junit.Assert.fail;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class TestUtil extends ClassLoader {

    public static byte[] readResource(String name) throws Exception {
        InputStream is = TestUtil.class.getResourceAsStream("/" + name);
        byte[] buf = new byte[65536];
        int len = is.read(buf);
        is.close();
        byte[] ret = new byte[len];
        System.arraycopy(buf, 0, ret, 0, len);
        return ret;
    }


    public static Object instantiate(SpyClassTransformer engine, String clazzName) throws Exception {
        TransformationResult result = transform(engine, clazzName);
        byte[] classBytes = result.transformedBytecode != null ? result.transformedBytecode : result.originalBytecode;

        Class<?> clazz = new TestUtil().defineClass(result.clazzName, classBytes, 0, classBytes.length);

        return clazz.newInstance();
    }

    public static TransformationResult transform(SpyClassTransformer engine, String clazzName) throws Exception {
        String className = clazzName.replace(".", "/");
        byte[] original = readResource(className + ".class");
        byte[] transformed = engine.transform(TestUtil.getSystemClassLoader(), className, null, null, original);

        return new TransformationResult(clazzName, original, transformed);
    }


    private static Field lookupField(Class<?> clazz, String fieldName) {

        for (Field f : clazz.getFields()) {
            if (fieldName.equals(f.getName())) {
                return f;
            }
        }

        for (Field f : clazz.getDeclaredFields()) {
            if (fieldName.equals(f.getName())) {
                return f;
            }
        }

        if (clazz.getSuperclass() != Object.class) {
            Field f = lookupField(clazz.getSuperclass(), fieldName);
            if (f != null) {
                return f;
            }
        }

        fail("Cannot find field " + fieldName + " in class " + clazz.getName());
        return null;
    }


    public static <T> T getField(Object obj, String fieldName) throws Exception {

        Field field = lookupField(obj.getClass(), fieldName);
        boolean accessible = field.isAccessible();
        field.setAccessible(true);

        Object retVal = field.get(obj);

        field.setAccessible(accessible);

        return (T) retVal;
    }


    public static Object invoke(Object obj, String name, Object... args) throws Exception {
        Method method = null;
        Class<?> clazz = obj.getClass();

        for (Method met : clazz.getMethods()) {
            if (name.equals(met.getName())) {
                method = met;
                break;
            }
        }

        if (method == null) {
            for (Method met : clazz.getDeclaredMethods()) {
                if (name.equals(met.getName())) {
                    method = met;
                    method.setAccessible(true);
                }
            }
        }

        try {

            if (method != null) {
                if (args.length == 0) {
                    return method.invoke(obj);
                } else {
                    return method.invoke(obj, args);
                }
            }
        } catch (InvocationTargetException e) {
            return e.getCause();
        } catch (Exception e) {
            return e;
        }

        return null;
    }


    public static Object checkForError(Object obj) {
        if (obj instanceof Throwable) {
            System.err.println("Error: " + obj);
            ((Throwable) obj).printStackTrace(System.err);
        }
        return obj;
    }


    public static Object getAttr(MBeanServerConnection mbs, String mbeanName, String attr) throws Exception {
        return mbs.getAttribute(new ObjectName(mbeanName), attr);
    }


    public static class TransformationResult {
        public final String clazzName;
        public final byte[] originalBytecode;
        public final byte[] transformedBytecode;

        private TransformationResult(String clazzName, byte[] originalBytecode, byte[] transformedBytecode) {
            this.clazzName = clazzName;
            this.originalBytecode = originalBytecode;
            this.transformedBytecode = transformedBytecode;
        }
    }
}
