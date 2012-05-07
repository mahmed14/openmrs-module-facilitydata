/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.facilitydata.service.db;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import org.hibernate.HibernateException;
import org.hibernate.engine.SessionImplementor;
import org.hibernate.type.Type;
import org.hibernate.type.TypeFactory;
import org.hibernate.usertype.ParameterizedType;
import org.hibernate.usertype.UserType;

import org.openmrs.api.context.Context;

/**
 * Taken primarily from https://www.hibernate.org/272.html
 * Written by Martin Kersten and tweaked by Gavin King
 *
 * Hibernate made non-backwards-compatible changes in 3.6,
 * which are used in OpenMRS 1.9 .  Code to accomodate both
 * was lifted from org.openmrs.module.reporting.report.service.db.GenericEnumUserType
 * and org.openmrs.module.reporting.report.service.db.HibernateUtil
 */
public class GenericEnumUserType implements UserType, ParameterizedType {
    private static final String DEFAULT_IDENTIFIER_METHOD_NAME = "name";
    private static final String DEFAULT_VALUE_OF_METHOD_NAME = "valueOf";

    private Class<? extends Enum> enumClass;
    private Class<?> identifierType;
    private Method identifierMethod;
    private Method valueOfMethod;
    private Type type;

    private int[] sqlTypes;

    public void setParameterValues(Properties parameters) {
        String enumClassName = parameters.getProperty("enumClass");
        try {
            enumClass = Class.forName(enumClassName).asSubclass(Enum.class);
        } catch (ClassNotFoundException cfne) {
            throw new HibernateException("Enum class not found", cfne);
        }

        String identifierMethodName = parameters.getProperty("identifierMethod", DEFAULT_IDENTIFIER_METHOD_NAME);

        try {
            identifierMethod = enumClass.getMethod(identifierMethodName, new Class[0]);
            identifierType = identifierMethod.getReturnType();
        } catch (Exception e) {
            throw new HibernateException("Failed to obtain identifier method", e);
        }

        type = GenericEnumUserType.getBasicType(identifierType.getName(), parameters);

        if (type == null)
            throw new HibernateException("Unsupported identifier type " + identifierType.getName());

        sqlTypes = new int[] { GenericEnumUserType.sqlType(type) };

        String valueOfMethodName = parameters.getProperty("valueOfMethod", DEFAULT_VALUE_OF_METHOD_NAME);

        try {
            valueOfMethod = enumClass.getMethod(valueOfMethodName, new Class[] { identifierType });
        } catch (Exception e) {
            throw new HibernateException("Failed to obtain valueOf method", e);
        }
    }

    public Class returnedClass() {
        return enumClass;
    }

    public Object nullSafeGet(ResultSet rs, String[] names, Object owner) throws HibernateException, SQLException {

	// in 3.2.5 it'd be type.get(rs, names[0]);
    	// in 3.6 it'd be type.get(rs, names[0], null);
    	Object identifier;
    	try {
    		identifier = type.getClass().getMethod("get", ResultSet.class, String.class).invoke(type, rs, names[0]);
    	} catch (Exception ex) {
    		try {
	            identifier = type.getClass().getMethod("get", ResultSet.class, String.class, SessionImplementor.class).invoke(type,  rs, names[0], null);
            }
            catch (Exception e) {
	            throw new RuntimeException("Error executing get method on " + type, e);
            }
    	}

        if (rs.wasNull()) {
            return null;
        }

        try {
            return valueOfMethod.invoke(enumClass, new Object[] { identifier });
        } catch (Exception e) {
            throw new HibernateException("Exception while invoking valueOf method '" + valueOfMethod.getName() + "' of " +
                    "enumeration class '" + enumClass + "'", e);
        }
    }

    public void nullSafeSet(PreparedStatement st, Object value, int index) throws HibernateException, SQLException {
        try {
            if (value == null) {
		st.setNull(index, GenericEnumUserType.sqlType(type));
            } else {
                Object identifier = identifierMethod.invoke(value, new Object[0]);

		// in both 3.2.5 and 3.6: type.set(st, identifier, index);
		type.getClass().getMethod("set", PreparedStatement.class, Object.class, int.class).invoke(type, st, identifier, index);
            }
        } catch (Exception e) {
            throw new HibernateException("Exception while invoking identifierMethod '" + identifierMethod.getName() + "' of " +
                    "enumeration class '" + enumClass + "'", e);
        }
    }

    public int[] sqlTypes() {
        return sqlTypes;
    }

    public Object assemble(Serializable cached, Object owner) throws HibernateException {
        return cached;
    }

    public Object deepCopy(Object value) throws HibernateException {
        return value;
    }

    public Serializable disassemble(Object value) throws HibernateException {
        return (Serializable) value;
    }

    public boolean equals(Object x, Object y) throws HibernateException {
        return x == y;
    }

    public int hashCode(Object x) throws HibernateException {
        return x.hashCode();
    }

    public boolean isMutable() {
        return false;
    }

    public Object replace(Object original, Object target, Object owner) throws HibernateException {
        return original;
    }


    /**
     * Copied from org.openmrs.module.reporting.report.service.db.HibernateUtil:
     * Hibernate made a non-backwards-compatible change in version 3.6 (which we
     * use starting in OpenMRS 1.9). See
     * https://hibernate.onjira.com/browse/HHH-5138. TypeFactory.basic no longer
     * exists.
     *
     * @param name
     * @return Given the name of a Hibernate basic type, return an instance of
     *         org.hibernate.type.Type
     */
    public static Type getBasicType(String name, Properties parameters) {
	try {
	    return TypeFactory.basic(name);
	} catch (NoSuchMethodError ex) {
	    // use reflection to do: return new
	    // TypeResolver().heuristicType(name, parameters);
	    try {
		Object typeResolver = Context.loadClass(
			"org.hibernate.type.TypeResolver").newInstance();
		Method method = typeResolver.getClass().getMethod(
			"heuristicType", String.class, Properties.class);
		return (Type) method.invoke(typeResolver, name, parameters);
	    } catch (Exception e) {
		throw new RuntimeException("Error getting hibernate type", e);
}
	}
    }

    /**
     * Copied from org.openmrs.module.reporting.report.service.db.HibernateUtil:
     * Hibernate made a non-backwards-compatible change in version 3.6 (which we
     * use starting in OpenMRS 1.9). See
     * https://hibernate.onjira.com/browse/HHH-5138.
     *
     * @param type
     * @return The JDBC type associated with the given Hibernate type
     */
    public static Integer sqlType(Type type) {
	try {
	    return (Integer) type.getClass().getMethod("sqlType").invoke(type);
	} catch (Exception ex) {
	    throw new RuntimeException("Error calling sqlType() method on "
		    + type, ex);
	}
    }

}
