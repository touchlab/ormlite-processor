package com.koenv.ormlite.processor;

import com.j256.ormlite.field.DataPersister;
import com.j256.ormlite.field.DataType;
import com.j256.ormlite.field.types.EnumStringType;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by kgalligan on 5/26/15.
 */
public class DataTypeManager
{
	private static final DataPersister DEFAULT_ENUM_PERSISTER = EnumStringType.getSingleton();

	private static final Map<String, DataType> builtInMap;
//	private static List<DataPersister> registeredPersisters = null;

	static {
		// add our built-in persisters
		builtInMap = new HashMap<String, DataType>();
		for (DataType dataType : DataType.values()) {
			DataPersister persister = dataType.getDataPersister();
			if (persister != null) {
				for (Class<?> clazz : persister.getAssociatedClasses()) {
					builtInMap.put(clazz.getName(), dataType);
				}
				String[] associatedClassNames = persister.getAssociatedClassNames();
				if (associatedClassNames != null) {
					for (String className : persister.getAssociatedClassNames()) {
						builtInMap.put(className, dataType);
					}
				}
			}
		}
	}

	private DataTypeManager() {
		// only for static methods
	}

/*	*//**
	 * Register a data type with the manager.
	 *//*
	public static void registerDataPersisters(DataPersister... dataPersisters) {
		// we build the map and replace it to lower the chance of concurrency issues
		List<DataPersister> newList = new ArrayList<DataPersister>();
		if (registeredPersisters != null) {
			newList.addAll(registeredPersisters);
		}
		for (DataPersister persister : dataPersisters) {
			newList.add(persister);
		}
		registeredPersisters = newList;
	}

	*//**
	 * Remove any previously persisters that were registered with {@link #registerDataPersisters(DataPersister...)}.
	 *//*
	public static void clear() {
		registeredPersisters = null;
	}*/

	/**
	 * Lookup the data-type associated with the class.
	 *
	 * @return The associated data-type interface or null if none found.
	 */
	public static DataType lookupForField(Element variableElement) {

		String fieldClassName = findFieldClassname(variableElement);
		/*// see if the any of the registered persisters are valid first
		if (registeredPersisters != null) {
			for (DataPersister persister : registeredPersisters) {
				if (persister.isValidForField(field)) {
					return persister;
				}
				// check the classes instead
				for (Class<?> clazz : persister.getAssociatedClasses()) {
					if (field.getType() == clazz) {
						return persister;
					}
				}
			}
		}*/

		// look it up in our built-in map by class
		DataType dataType = builtInMap.get(fieldClassName);
		if (dataType != null) {
			return dataType;
		}

		/*
		 * Special case for enum types. We can't put this in the registered persisters because we want people to be able
		 * to override it.
		 */
		/*if (field.getType().isEnum()) {
			return DEFAULT_ENUM_PERSISTER;
		} else {
			*//*
			 * Serializable classes return null here because we don't want them to be automatically configured for
			 * forwards compatibility with future field types that happen to be Serializable.
			 *//*
			return null;
		}*/
		throw new RuntimeException("fuck");
	}

	public static String findFieldClassname(Element fieldElement)
	{
		TypeMirror typeMirror = fieldElement.asType();
		TypeKind kind = typeMirror.getKind();
		if(kind.isPrimitive())
		{
			Class primitiveClass = null;
			switch (kind)
			{
				case BOOLEAN:
					primitiveClass = boolean.class;
					break;
				case DOUBLE:
					primitiveClass = double.class;
					break;
				case FLOAT:
					primitiveClass = float.class;
					break;
				case INT:
					primitiveClass = int.class;
					break;
				case LONG:
					primitiveClass = long.class;
					break;
				case SHORT:
					primitiveClass = short.class;
					break;
				default:
					throw new UnsupportedOperationException("Don't recognize type: "+ kind);
			}

			return primitiveClass.toString();
		}
		else
		{
			DeclaredType declaredType = (DeclaredType) typeMirror;
			return ((TypeElement) declaredType.asElement()).getQualifiedName().toString();
		}
	}
}
