package com.koenv.ormlite.processor;

import com.j256.ormlite.dao.BaseDaoImpl;
import com.j256.ormlite.db.DatabaseType;
import com.j256.ormlite.field.DataPersister;
import com.j256.ormlite.field.DataType;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.field.FieldConverter;
import com.j256.ormlite.stmt.mapped.MappedQueryForId;
import com.j256.ormlite.table.DatabaseTable;
import com.j256.ormlite.table.TableInfo;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.processing.Messager;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.sql.SQLException;

/**
 * Created by kgalligan on 5/26/15.
 */
public class FieldTypeGen
{
	private static boolean DEFAULT_VALUE_BOOLEAN;
	private static byte DEFAULT_VALUE_BYTE;
	private static char DEFAULT_VALUE_CHAR;
	private static short DEFAULT_VALUE_SHORT;
	private static int DEFAULT_VALUE_INT;
	private static long DEFAULT_VALUE_LONG;
	private static float DEFAULT_VALUE_FLOAT;
	private static double DEFAULT_VALUE_DOUBLE;

	public String tableName;
	public final String columnName;
	public final boolean isId;
	public final boolean isGeneratedId;
	public final boolean foreign;
	public final boolean useGetSet;
	public final DatabaseField databaseField;
	public final String dataTypeClassname;

	//TypeMirror?
	public TypeMirror parentClass;
	public String fieldName;

	public DataType dataType;
	public String persisterClass;
	public Object defaultValue;
//	private Object dataTypeConfigObj;

	private FieldConverter fieldConverter;

	private FieldTypeGen foreignIdField;
	private TableInfo<?, ?> foreignTableInfo;
	private FieldTypeGen foreignFieldType;
	private BaseDaoImpl<?, ?> foreignDao;
	private MappedQueryForId<Object, Object> mappedQueryForId;


	public FieldTypeGen(DatabaseType databaseType, Element databaseElement, Element fieldElement, Types typeUtils, Messager messager)
	{
		databaseField = fieldElement.getAnnotation(DatabaseField.class);

//		FieldBindings fieldBindings = FieldBindings.fromDatabaseField(databaseType, fieldElement, databaseField, typeUtils, messager);
		this.tableName = extractTableName((TypeElement) databaseElement);
		this.fieldName = fieldElement.getSimpleName().toString();;
		VariableElement variableElement = (VariableElement) fieldElement;
		dataTypeClassname = DataTypeManager.findFieldClassname(variableElement);
		this.columnName = extractColumnName(variableElement);
		this.foreign = databaseField.foreign();

		this.useGetSet = databaseField.useGetSet() || !variableElement.getModifiers().contains(Modifier.PUBLIC);

		this.parentClass = ((TypeElement) databaseElement).getSuperclass();

		// post process our config settings
//		fieldConfig.postProcess();

		dataType = DataTypeManager.lookupForField(variableElement);
		persisterClass = findPersisterClass(databaseField, typeUtils, messager);

		//OH SHIT FOREIGN
/*
		String foreignColumnName = fieldConfig.getForeignColumnName();
		String defaultFieldName = fieldName;
		if (fieldConfig.isForeign() || fieldConfig.isForeignAutoRefresh() || foreignColumnName != null) {
			if (dataPersister != null && dataPersister.isPrimitive()) {
				throw new IllegalArgumentException("Field " + this + " is a primitive class "
						+ " but marked as foreign");
			}
			if (foreignColumnName == null) {
				defaultFieldName = defaultFieldName + FOREIGN_ID_FIELD_SUFFIX;
			} else {
				defaultFieldName = defaultFieldName + "_" + foreignColumnName;
			}
		}  else if (dataPersister == null && (!fieldConfig.isForeignCollection())) {
			if (byte[].class.isAssignableFrom(clazz)) {
				throw new SQLException("ORMLite does not know how to store " + clazz + " for field '" + fieldName
						+ "'. byte[] fields must specify dataType=DataType.BYTE_ARRAY or SERIALIZABLE");
			} else if (Serializable.class.isAssignableFrom(clazz)) {
				throw new SQLException("ORMLite does not know how to store " + clazz + " for field '" + fieldName
						+ "'.  Use another class, custom persister, or to serialize it use "
						+ "dataType=DataType.SERIALIZABLE");
			} else {
				throw new IllegalArgumentException("ORMLite does not know how to store " + clazz + " for field "
						+ fieldName + ". Use another class or a custom persister.");
			}
		}*/

		if (databaseField.id()) {
			if (databaseField.generatedId()) {
				throw new IllegalArgumentException("Must specify one of id, generatedId, and generatedIdSequence with "
						+ fieldName);
			}
			this.isId = true;
			this.isGeneratedId = false;
		} else if (databaseField.generatedId()) {
			this.isId = true;
			this.isGeneratedId = true;
		} else {
			this.isId = false;
			this.isGeneratedId = false;
		}
		if (this.isId && (databaseField.foreign() || databaseField.foreignAutoRefresh()))
		{
			throw new IllegalArgumentException("Id field " + fieldName + " cannot also be a foreign object");
		}
		//extra validation
		/*if (fieldConfig.isAllowGeneratedIdInsert() && !fieldConfig.isGeneratedId()) {
			throw new IllegalArgumentException("Field " + field.getName()
					+ " must be a generated-id if allowGeneratedIdInsert = true");
		}
		if (fieldConfig.isForeignAutoRefresh() && !fieldConfig.isForeign()) {
			throw new IllegalArgumentException("Field " + field.getName()
					+ " must have foreign = true if foreignAutoRefresh = true");
		}
		if (fieldConfig.isForeignAutoCreate() && !fieldConfig.isForeign()) {
			throw new IllegalArgumentException("Field " + field.getName()
					+ " must have foreign = true if foreignAutoCreate = true");
		}
		if (fieldConfig.getForeignColumnName() != null && !fieldConfig.isForeign()) {
			throw new IllegalArgumentException("Field " + field.getName()
					+ " must have foreign = true if foreignColumnName is set");
		}
		if (fieldConfig.isVersion() && (dataPersister == null || !dataPersister.isValidForVersion())) {
			throw new IllegalArgumentException("Field " + field.getName()
					+ " is not a valid type to be a version field");
		}*/
		try
		{
			assignDataType(databaseField);
		} catch (SQLException e)
		{
			throw new RuntimeException(e);
		}
	}

	private void assignDataType(DatabaseField databaseField) throws SQLException {

		if (dataType == null) {
			if (!databaseField.foreign()) {
				// may never happen but let's be careful out there
				throw new SQLException("Data persister for field " + this
						+ " is null but the field is not a foreign or foreignCollection");
			}
			return;
		}
		DataPersister dataPersister = dataType.getDataPersister();

		if (this.isGeneratedId && !dataPersister.isValidGeneratedType()) {
			StringBuilder sb = new StringBuilder();
			sb.append("Generated-id field '").append(fieldName);
			sb.append("' in ").append(tableName);
			sb.append(" can't be type ").append(dataPersister.getSqlType());
			sb.append(".  Must be one of: ");
			for (DataType dataType : DataType.values()) {
				DataPersister persister = dataType.getDataPersister();
				if (persister != null && persister.isValidGeneratedType()) {
					sb.append(dataType).append(' ');
				}
			}
			throw new IllegalArgumentException(sb.toString());
		}
		if (databaseField.throwIfNull() && !dataPersister.isPrimitive()) {
			throw new SQLException("Field " + fieldName + " must be a primitive if set with throwIfNull");
		}
		if (this.isId && !dataPersister.isAppropriateId()) {
			throw new SQLException("Field '" + fieldName + "' is of data type " + dataPersister
					+ " which cannot be the ID field");
		}
//		this.dataTypeConfigObj = dataPersister.makeConfigObject(this);
		String defaultStr = databaseField.defaultValue();
		if (defaultStr == null || defaultStr.equals(DatabaseField.DEFAULT_STRING)) {
			this.defaultValue = null;
		} else if (this.isGeneratedId) {
			throw new SQLException("Field '" + fieldName + "' cannot be a generatedId and have a default value '"
					+ defaultStr + "'");
		} else {
			//TODO
//			this.defaultValue = this.fieldConverter.parseDefaultString(this, defaultStr);
		}
	}

	private String findFieldClassname(VariableElement fieldElement)
	{
		TypeMirror typeMirror = fieldElement.asType();
		DeclaredType declaredType = (DeclaredType) typeMirror;

		return ((TypeElement) declaredType.asElement()).getQualifiedName().toString();
	}

	/*private DataType findDataType(VariableElement fieldElement)
	{
		DataType dataType = null;
		
		TypeMirror typeMirror = fieldElement.asType();
		DeclaredType declaredType = (DeclaredType) typeMirror;


		TypeKind kind = typeMirror.getKind();
		if(kind.isPrimitive())
		{
			switch (kind)
			{
				case BOOLEAN:
					dataType = DataType.BOOLEAN;
					break;
				case DOUBLE:
					dataType = DataType.DOUBLE;
					break;
				case FLOAT:
					dataType = DataType.FLOAT;
					break;
				case INT:
					dataType = DataType.INTEGER;
					break;
				case LONG:
					dataType = DataType.LONG;
					break;
				case SHORT:
					dataType = DataType.SHORT;
					break;
				default:
					throw new UnsupportedOperationException("Don't recognize type: "+ kind);
			}
		}
		else
		{
			String theType = ((TypeElement) declaredType.asElement()).getQualifiedName().toString();
			if(theType.equals("java.lang.String"))
				dataType = DataType.STRING;
		}

		return dataType;
	}*/

	private static String extractTableName(TypeElement element) {
		DatabaseTable databaseTable = element.getAnnotation(DatabaseTable.class);
		String name;
		if (databaseTable != null && databaseTable.tableName() != null && databaseTable.tableName().length() > 0) {
			name = databaseTable.tableName();
		} else {
			// if the name isn't specified, it is the class name lowercased
			name = element.getSimpleName().toString().toLowerCase();
		}
		return name;
	}

	private static String extractColumnName(VariableElement element) {
		DatabaseField databaseField = element.getAnnotation(DatabaseField.class);
		String columnName = StringUtils.trimToNull(databaseField.columnName());
		if(columnName == null)
			columnName = element.getSimpleName().toString();

		return columnName;
	}

	private static String findPersisterClass(DatabaseField databaseField, Types typeUtils, Messager messager)
	{
		TypeElement persisterClass = null;
		try {
			databaseField.persisterClass();
		} catch (MirroredTypeException e) {
			Element element = typeUtils.asElement(e.getTypeMirror());
			if (!element.getKind().equals(ElementKind.CLASS)) {
				messager.printMessage(Diagnostic.Kind.ERROR, "persisterClass must be a class", element);
				return null;
			}
			persisterClass = (TypeElement) element;
		}

		if (persisterClass != null && !persisterClass.getQualifiedName().toString().equals("com.j256.ormlite.field.types.VoidType")) {
			return persisterClass.getQualifiedName().toString();
		}

		return null;
	}

	/**
	 * Because we go recursive in a lot of situations if we construct DAOs inside of the FieldType constructor, we have
	 * to do this 2nd pass initialization so we can better use the DAO caches.
	 *
	 * @see BaseDaoImpl#initialize()
	 */
	/*public void configDaoInformation(ConnectionSource connectionSource, Class<?> parentClass) throws SQLException {
		Class<?> fieldClass = field.getType();
		DatabaseType databaseType = connectionSource.getDatabaseType();
		TableInfo<?, ?> foreignTableInfo;
		final FieldType foreignIdField;
		final FieldType foreignFieldType;
		final BaseDaoImpl<?, ?> foreignDao;
		final MappedQueryForId<Object, Object> mappedQueryForId;

		String foreignColumnName = fieldConfig.getForeignColumnName();
		if (fieldConfig.isForeignAutoRefresh() || foreignColumnName != null) {
			DatabaseTableConfig<?> tableConfig = fieldConfig.getForeignTableConfig();
			if (tableConfig == null) {
				// NOTE: the cast is necessary for maven
				foreignDao = (BaseDaoImpl<?, ?>) DaoManager.createDao(connectionSource, fieldClass);
				foreignTableInfo = foreignDao.getTableInfo();
			} else {
				tableConfig.extractFieldTypes(connectionSource);
				// NOTE: the cast is necessary for maven
				foreignDao = (BaseDaoImpl<?, ?>) DaoManager.createDao(connectionSource, tableConfig);
				foreignTableInfo = foreignDao.getTableInfo();
			}
			if (foreignColumnName == null) {
				foreignIdField = foreignTableInfo.getIdField();
				if (foreignIdField == null) {
					throw new IllegalArgumentException("Foreign field " + fieldClass + " does not have id field");
				}
			} else {
				foreignIdField = foreignTableInfo.getFieldTypeByColumnName(foreignColumnName);
				if (foreignIdField == null) {
					throw new IllegalArgumentException("Foreign field " + fieldClass + " does not have field named '"
							+ foreignColumnName + "'");
				}
			}
			@SuppressWarnings("unchecked")
			MappedQueryForId<Object, Object> castMappedQueryForId =
					(MappedQueryForId<Object, Object>) MappedQueryForId.build(databaseType, foreignTableInfo,
							foreignIdField);
			mappedQueryForId = castMappedQueryForId;
			foreignFieldType = null;
		} else if (fieldConfig.isForeign()) {
			if (this.dataPersister != null && this.dataPersister.isPrimitive()) {
				throw new IllegalArgumentException("Field " + this + " is a primitive class " + fieldClass
						+ " but marked as foreign");
			}
			DatabaseTableConfig<?> tableConfig = fieldConfig.getForeignTableConfig();
			if (tableConfig != null) {
				tableConfig.extractFieldTypes(connectionSource);
				// NOTE: the cast is necessary for maven
				foreignDao = (BaseDaoImpl<?, ?>) DaoManager.createDao(connectionSource, tableConfig);
			} else {
				*//*
				 * Initially we were only doing this just for BaseDaoEnabled.class and isForeignAutoCreate(). But we
				 * need it also for foreign fields because the alternative was to use reflection. Chances are if it is
				 * foreign we're going to need the DAO in the future anyway so we might as well create it. This also
				 * allows us to make use of any table configs.
				 *//*
				// NOTE: the cast is necessary for maven
				foreignDao = (BaseDaoImpl<?, ?>) DaoManager.createDao(connectionSource, fieldClass);
			}
			foreignTableInfo = foreignDao.getTableInfo();
			foreignIdField = foreignTableInfo.getIdField();
			if (foreignIdField == null) {
				throw new IllegalArgumentException("Foreign field " + fieldClass + " does not have id field");
			}
			if (isForeignAutoCreate() && !foreignIdField.isGeneratedId()) {
				throw new IllegalArgumentException("Field " + field.getName()
						+ ", if foreignAutoCreate = true then class " + fieldClass.getSimpleName()
						+ " must have id field with generatedId = true");
			}
			foreignFieldType = null;
			mappedQueryForId = null;
		} else {
			foreignTableInfo = null;
			foreignIdField = null;
			foreignFieldType = null;
			foreignDao = null;
			mappedQueryForId = null;
		}

		this.mappedQueryForId = mappedQueryForId;
		this.foreignTableInfo = foreignTableInfo;
		this.foreignFieldType = foreignFieldType;
		this.foreignDao = foreignDao;
		this.foreignIdField = foreignIdField;

		// we have to do this because if we habe a foreign field then our id type might have gone to an _id primitive
		if (this.foreignIdField != null) {
			assignDataType(databaseType, this.foreignIdField.getDataPersister());
		}
	}*/
}
