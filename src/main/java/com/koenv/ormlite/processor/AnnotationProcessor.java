/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Koen Vlaswinkel
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.koenv.ormlite.processor;

import com.google.common.base.Joiner;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.db.DatabaseType;
import com.j256.ormlite.db.SqliteAndroidDatabaseType;
import com.j256.ormlite.field.DataType;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.field.FieldType;
import com.j256.ormlite.support.DatabaseResults;
import com.j256.ormlite.table.DatabaseTable;
import com.j256.ormlite.table.DatabaseTableConfig;
import com.j256.ormlite.table.GeneratedTableMapper;
import com.squareup.javapoet.*;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;

public class AnnotationProcessor extends AbstractProcessor
{
//    private static final int DEFAULT_MAX_EAGER_FOREIGN_COLLECTION_LEVEL = ForeignCollectionField.DEFAULT_MAX_EAGER_LEVEL;

	private Types typeUtils;
	private Filer filer;
	private Messager messager;

	private static final DatabaseType databaseType = new SqliteAndroidDatabaseType();

	private List<ClassName> baseClasses;
	private List<ClassName> generatedClasses;
	private Elements elementUtils;

	@Override
	public synchronized void init(ProcessingEnvironment processingEnv)
	{
		super.init(processingEnv);
		typeUtils = processingEnv.getTypeUtils();
		elementUtils = processingEnv.getElementUtils();
		filer = processingEnv.getFiler();
		messager = processingEnv.getMessager();
	}

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv)
	{
		try
		{
			return safeProcess(roundEnv);
		} catch (Exception e)
		{
			StringWriter sq = new StringWriter();
			PrintWriter printWriter = new PrintWriter(sq);
			e.printStackTrace(printWriter);
			messager.printMessage(Diagnostic.Kind.ERROR, e.getClass().getName() + "\n\n" + sq
					.toString() + "\n\n");
			return true;
		}
	}

	private class DatabaseTableHolder
	{
		public final Element annoDatabaseTableElement;
		public final List<FieldTypeGen> fieldTypeGens;
		public final TypeElement typeElement;
		public final String tableName;

		public DatabaseTableHolder(Element annoDatabaseTableElement, List<FieldTypeGen> fieldTypeGens, TypeElement typeElement, String tableName)
		{
			this.annoDatabaseTableElement = annoDatabaseTableElement;
			this.fieldTypeGens = fieldTypeGens;
			this.typeElement = typeElement;
			this.tableName = tableName;
		}
	}
	private boolean safeProcess(RoundEnvironment roundEnv)
	{
		baseClasses = new ArrayList<ClassName>();
		generatedClasses = new ArrayList<ClassName>();

		List<DatabaseTableHolder> tableHolders = new ArrayList<DatabaseTableHolder>();

		for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(DatabaseTable.class))
		{
			if (!annotatedElement.getKind().isClass())
			{
				error(annotatedElement, "Only classes can be annotated with %s", DatabaseTable.class.getSimpleName());
				return false;
			}
			TypeElement typeElement = (TypeElement) annotatedElement;
			String tableName = extractTableName(typeElement);
			List<FieldTypeGen> fieldTypeGens = new ArrayList<FieldTypeGen>();
			// walk up the classes finding the fields
			TypeElement working = typeElement;
			while (working != null)
			{
				for (Element element : working.getEnclosedElements())
				{

					if (element.getKind().isField())
					{
						if (element.getAnnotation(DatabaseField.class) != null)
						{
							DatabaseField databaseField = element.getAnnotation(DatabaseField.class);
							if (!databaseField.persisted())
							{
								continue;
							}
							FieldTypeGen fieldTypeGen = new FieldTypeGen(databaseType, annotatedElement, element, typeUtils, messager);

							if (fieldTypeGen != null)
							{
								fieldTypeGens.add(fieldTypeGen);
							}

						} /*else if (element.getAnnotation(ForeignCollectionField.class) != null) {
							ForeignCollectionField foreignCollectionField = element.getAnnotation(ForeignCollectionField.class);
                            FieldBindings fieldConfig = FieldBindings.fromForeignCollection(element, foreignCollectionField);
                            if (fieldConfig != null) {
                                fieldConfigs.add(fieldConfig);
                            }
                        }*/
					}
				}
				if (working.getSuperclass().getKind().equals(TypeKind.NONE))
				{
					break;
				}
				working = (TypeElement) typeUtils.asElement(working.getSuperclass());
			}
			if (fieldTypeGens.isEmpty())
			{
				error(
						typeElement,
						"Every class annnotated with %s must have at least 1 field annotated with %s",
						DatabaseTable.class.getSimpleName(),
						DatabaseField.class.getSimpleName()
				);
				return false;
			}

			tableHolders.add(new DatabaseTableHolder(annotatedElement, fieldTypeGens, typeElement, tableName));
		}

		for (DatabaseTableHolder tableHolder : tableHolders)
		{
			JavaFile javaFile = generateClassConfigFile(tableHolders, tableHolder.typeElement, tableHolder.fieldTypeGens, tableHolder.tableName);

			try
			{
				javaFile.writeTo(filer);
			} catch (IOException e)
			{
				error(tableHolder.typeElement, "Code gen failed: " + e);
				return false;
			}
		}

		if (!generatedClasses.isEmpty())
		{
			JavaFile javaFile = generateMainFile();
			JavaFile helperJavaFile = generateHelperFile();
			try
			{
				javaFile.writeTo(filer);
				helperJavaFile.writeTo(filer);
			} catch (IOException e)
			{
				messager.printMessage(Diagnostic.Kind.ERROR, "Code gen failed: failed to generate main class: " + e);
				return false;
			}
		}

		return false;
	}

	private JavaFile generateMainFile()
	{
		ClassName className = ClassName.get("com.koenv.ormlite.processor", "OrmLiteProcessor");

		TypeSpec.Builder configBuilder = TypeSpec.classBuilder(className.simpleName())
				.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
				.addJavadoc("Generated on $L\n", new SimpleDateFormat("yyyy/MM/dd hh:mm:ss").format(new Date()));

		ParameterizedTypeName databaseTableConfig = ParameterizedTypeName.get(ClassName.get(DatabaseTableConfig.class), WildcardTypeName.subtypeOf(Object.class));

		ParameterizedTypeName collectionOfTableConfigs = ParameterizedTypeName.get(ClassName.get(Collection.class), databaseTableConfig);
		ParameterizedTypeName listOfTableConfigs = ParameterizedTypeName.get(ClassName.get(ArrayList.class), databaseTableConfig);

		MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("init")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
				.returns(TypeName.VOID)
				.addException(SQLException.class)
				.addJavadoc("Call this method when initializing your application\n")
				.addStatement("$T configs = new $T()", collectionOfTableConfigs, listOfTableConfigs);

		for (ClassName tableConfig : generatedClasses)
		{
			methodBuilder.addStatement("configs.add($T.getTableConfig())", tableConfig);
		}

		ParameterizedTypeName generatedMapType = ParameterizedTypeName.get(ClassName.get(HashMap.class),
				ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(Object.class)),
				ParameterizedTypeName.get(ClassName.get(GeneratedTableMapper.class), WildcardTypeName.subtypeOf(Object.class), WildcardTypeName.subtypeOf(Object.class)));
		methodBuilder.addStatement("$T generatedMap = new $T()", generatedMapType, generatedMapType);
		for (int i = 0; i < baseClasses.size(); i++)
		{
			ClassName baseClass = baseClasses.get(i);
			ClassName generatedClass = generatedClasses.get(i);
			methodBuilder.addStatement("generatedMap.put($T.class, new $T())", baseClass, generatedClass);
		}
		methodBuilder.addStatement("$T.setGeneratedMap(generatedMap)", DaoManager.class);
		methodBuilder.addStatement("$T.addCachedDatabaseConfigs(configs)", DaoManager.class);

		configBuilder.addMethod(methodBuilder.build());

		return JavaFile.builder(className.packageName(), configBuilder.build()).build();
	}

	private JavaFile generateHelperFile()
	{
		ClassName className = ClassName.get("com.koenv.ormlite.processor", "OrmLiteHelper");

		TypeSpec.Builder configBuilder = TypeSpec.classBuilder(className.simpleName())
				.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
				.addJavadoc("Generated on $L\n", new SimpleDateFormat("yyyy/MM/dd hh:mm:ss").format(new Date()));

		MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("findDbColumn")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
				.addException(SQLException.class)
				.addParameter(DatabaseResults.class, "results")
				.addParameter(createColPositionsType(), "columnPositions")
				.addParameter(String.class, "columnName")
				.returns(int.class)
				.addJavadoc("Find db column and fill map\n");

		methodBuilder.addCode("\t\tInteger dbColumnPos = columnPositions.get(columnName);\n" +
				"\t\tif (dbColumnPos == null) {\n" +
				"\t\t\tdbColumnPos = results.findColumn(columnName);\n" +
				"\t\t\tcolumnPositions.put(columnName, dbColumnPos);\n" +
				"\t\t}\n" +
				"\t\treturn dbColumnPos;\n");

		configBuilder.addMethod(methodBuilder.build());

		return JavaFile.builder(className.packageName(), configBuilder.build()).build();
	}

	private ParameterizedTypeName createColPositionsType()
	{
		return ParameterizedTypeName.get(Map.class, String.class, Integer.class);
	}

	private JavaFile generateClassConfigFile(List<DatabaseTableHolder> databaseTableHolders, TypeElement element, List<FieldTypeGen> fieldTypeGens, String tableName)
	{
		ConfigureClassDefinitions configureClassDefinitions = new ConfigureClassDefinitions(databaseTableHolders, element).invoke();
		ClassName configName = configureClassDefinitions.getConfigName();
		ClassName className = configureClassDefinitions.getClassName();
		ClassName idType = configureClassDefinitions.getIdType();

		FieldSpec staticInstanceField = FieldSpec.builder(configName, "instance", Modifier.PUBLIC, Modifier.FINAL, Modifier.STATIC)
				.initializer("new $T()", configName)
				.build();

		TypeSpec.Builder configBuilder = TypeSpec.classBuilder(configName.simpleName())
				.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
				.addField(FieldType[].class, "fieldConfigs")
				.addField(staticInstanceField)
				.addSuperinterface(ParameterizedTypeName.get(ClassName.get(GeneratedTableMapper.class), className, idType))
				.addJavadoc("Generated on $L\n", new SimpleDateFormat("yyyy/MM/dd hh:mm:ss").format(new Date()));


		MethodSpec constructor = MethodSpec.constructorBuilder()
				.addModifiers(Modifier.PUBLIC)
//				.addException(SQLException.class)
				.addStatement("this.$N = $N()", "fieldConfigs", "getFieldConfigs")
				.build();

		configBuilder.addMethod(constructor);

		createObject(className, configBuilder);
		fillRow(databaseTableHolders, element, fieldTypeGens, tableName, className, configBuilder);
		assignVersion(className, configBuilder);
		assignId(fieldTypeGens, className, configBuilder);
		extractId(fieldTypeGens, className, configBuilder);
		extractVersion(fieldTypeGens, className, configBuilder);
		extractVals(databaseTableHolders, fieldTypeGens, className, configBuilder, "extractVals", false);
		extractVals(databaseTableHolders, fieldTypeGens, className, configBuilder, "extractCreateVals", true);

		MethodSpec fieldConfigsMethod = fieldConfigs(fieldTypeGens, tableName, className, configBuilder);

		tableConfig(element, tableName, className, configBuilder, fieldConfigsMethod);

		baseClasses.add(className);
		generatedClasses.add(configName);

		return JavaFile.builder(configName.packageName(), configBuilder.build()).build();
	}

	private void tableConfig(TypeElement element, String tableName, ClassName className, TypeSpec.Builder configBuilder, MethodSpec fieldConfigsMethod)
	{
		TypeName databaseTableConfig = ParameterizedTypeName.get(ClassName.get(DatabaseTableConfig.class), className);
		MethodSpec.Builder tableConfigMethodBuilder = MethodSpec.methodBuilder("getTableConfig")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
				.addException(SQLException.class)
				.returns(databaseTableConfig)
				.addStatement("$T config = new $T($T.class, $S, $N())",
						databaseTableConfig,
						databaseTableConfig,
						element,
						tableName,
						fieldConfigsMethod
				);

		tableConfigMethodBuilder.addStatement("return config");

		configBuilder.addMethod(tableConfigMethodBuilder.build());
	}

	private MethodSpec fieldConfigs(List<FieldTypeGen> fieldTypeGens, String tableName, ClassName className, TypeSpec.Builder configBuilder)
	{
		TypeName listOfFieldConfigs = ParameterizedTypeName.get(List.class, FieldType.class);
		TypeName arrayListOfFieldConfigs = ParameterizedTypeName.get(ArrayList.class, FieldType.class);

		MethodSpec.Builder fieldConfigsMethodBuilder = MethodSpec.methodBuilder("getFieldConfigs")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
//				.addException(SQLException.class)
				.returns(FieldType[].class)
				.addStatement("$T list = new $T()", listOfFieldConfigs, arrayListOfFieldConfigs);

		fieldConfigsMethodBuilder.addStatement("$T config = null", FieldType.class);

		for (FieldTypeGen config : fieldTypeGens)
		{
			fieldConfigsMethodBuilder.addCode(getFieldConfig(config, config.databaseField, tableName, className));
			fieldConfigsMethodBuilder.addStatement("list.add(config)");
		}

		fieldConfigsMethodBuilder.addStatement("return list.toArray(new FieldType[list.size()])");

		MethodSpec fieldConfigsMethod = fieldConfigsMethodBuilder.build();

		configBuilder.addMethod(fieldConfigsMethod);
		return fieldConfigsMethod;
	}

	private void createObject(ClassName className, TypeSpec.Builder configBuilder)
	{
		MethodSpec.Builder javaFillMethodBuilder = MethodSpec.methodBuilder("createObject")
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(Override.class)
				.returns(className);

		javaFillMethodBuilder.addStatement("$T data = new $T()", className, className);
		javaFillMethodBuilder.addStatement("return data");

		configBuilder.addMethod(javaFillMethodBuilder.build());
	}

	private void fillRow(List<DatabaseTableHolder> databaseTableHolders, TypeElement element, List<FieldTypeGen> fieldTypeGens, String tableName, ClassName className, TypeSpec.Builder configBuilder)
	{
		MethodSpec.Builder javaFillMethodBuilder = MethodSpec.methodBuilder("fillRow")
				.addModifiers(Modifier.PUBLIC)
				.addParameter(className, "data")
				.addParameter(DatabaseResults.class, "results")
				.addException(SQLException.class)
				.addAnnotation(Override.class);

		makeCopyRows(databaseTableHolders, javaFillMethodBuilder, element, tableName, fieldTypeGens);

		configBuilder.addMethod(javaFillMethodBuilder.build());
	}

	private void assignVersion(ClassName className, TypeSpec.Builder configBuilder)
	{
		configBuilder.addMethod(MethodSpec
						.methodBuilder("assignVersion")
						.addModifiers(Modifier.PUBLIC)
						.addAnnotation(Override.class)
						.addParameter(className, "data")
						.addParameter(Object.class, "val")
						.build()
		);
	}

	private void assignId(List<FieldTypeGen> fieldTypeGens, ClassName className, TypeSpec.Builder configBuilder)
	{
		FieldTypeGen idField = findIdField(fieldTypeGens);

		MethodSpec.Builder methodBuilder = MethodSpec
				.methodBuilder("assignId")
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(Override.class)
				.addParameter(className, "data")
				.addParameter(Object.class, "val");

		if(idField.useGetSet)
			methodBuilder.addCode(CodeBlock.builder()
							.addStatement("data.set$N(($N)val)", StringUtils.capitalize(idField.fieldName), idField.dataTypeClassname)
							.build()
			);
		else
			methodBuilder.addCode(CodeBlock.builder()
							.addStatement("data.$N = ($N)val", idField.fieldName, idField.dataTypeClassname)
							.build()
			);

		configBuilder.addMethod(methodBuilder
						.build()
		);
	}

	private void extractId(List<FieldTypeGen> fieldTypeGens, ClassName className, TypeSpec.Builder configBuilder)
	{
		FieldTypeGen idField = findIdField(fieldTypeGens);

		MethodSpec.Builder methodBody = MethodSpec
				.methodBuilder("extractId")
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(Override.class)

				.addParameter(className, "data")
				.returns(ClassName.bestGuess(idField.dataTypeClassname));

		if (idField.useGetSet)
			methodBody.addStatement("return data.get"+ StringUtils.capitalize(idField.fieldName) +"()");
		else
			methodBody.addStatement("return data."+ idField.fieldName);

		configBuilder.addMethod(methodBody
						.build()
		);
	}

	private FieldTypeGen findIdField(List<FieldTypeGen> fieldTypeGens)
	{
		FieldTypeGen idField = null;
		for (FieldTypeGen fieldTypeGen : fieldTypeGens)
		{
			if (fieldTypeGen.isId || fieldTypeGen.isGeneratedId)
			{
				idField = fieldTypeGen;
			}
		}

		if (idField == null)
			throw new IllegalArgumentException("Need id");
		return idField;
	}

	private void extractVersion(List<FieldTypeGen> fieldTypeGens, ClassName className, TypeSpec.Builder configBuilder)
	{
		MethodSpec.Builder methodBody = MethodSpec
				.methodBuilder("extractVersion")
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(Override.class)
				.addParameter(className, "data")
				.returns(Object.class);

		methodBody.addStatement("return 0");

		configBuilder.addMethod(methodBody
						.build()
		);
	}

	private static DataType[] STATIC_TYPES =  new DataType[]{
			DataType.BOOLEAN,
			DataType.BOOLEAN_OBJ,
			DataType.DOUBLE,
			DataType.DOUBLE_OBJ,
			DataType.FLOAT,
			DataType.FLOAT_OBJ,
			DataType.INTEGER,
			DataType.INTEGER_OBJ,
			DataType.LONG,
			DataType.LONG_OBJ,
			DataType.SHORT,
			DataType.SHORT_OBJ,
			DataType.STRING
	};

	private static boolean isStaticType(DataType dataType)
	{
		for (DataType staticType : STATIC_TYPES)
		{
			if(staticType == dataType)
				return true;
		}

		return false;
	}

	private void extractVals(List<DatabaseTableHolder> databaseTableHolders, List<FieldTypeGen> fieldTypeGens, ClassName className, TypeSpec.Builder configBuilder, String methodName, boolean createVals)
	{
		MethodSpec.Builder returns = MethodSpec
				.methodBuilder(methodName)
				.addModifiers(Modifier.PUBLIC)
				.addException(SQLException.class)
				.addAnnotation(Override.class)
				.addParameter(className, "data")
				.returns(Object[].class);

		
		List<String> assignStatements = new ArrayList<String>();
		List<String> convertStatements = new ArrayList<String>();

		int count = 0;
		int configCount = 0;
		for (FieldTypeGen fieldTypeGen : fieldTypeGens)
		{
			StringBuilder sb = new StringBuilder();

			if(!(createVals && fieldTypeGen.isGeneratedId))
			{
				if (fieldTypeGen.useGetSet)
				{
					sb.append("fields[").append(count).append("] = data.get").append(StringUtils.capitalize(fieldTypeGen.fieldName)).append("(").append(")");
				} else
				{
					sb.append("fields[").append(count).append("] = data.").append(fieldTypeGen.fieldName).append("");
				}

				StringBuilder convertBuilder = null;
				if(!isStaticType(fieldTypeGen.dataType))
				{
					if(fieldTypeGen.foreign)
					{
						ConfigureClassDefinitions configureClassDefinitions = new ConfigureClassDefinitions(databaseTableHolders, (TypeElement)fieldTypeGen.databaseElement).invoke();
						convertBuilder = new StringBuilder();
						convertBuilder.append("fields[").append(count).append("] = ").append(configureClassDefinitions.configName.toString()).append(".instance.extractId(").append("fields[").append(count).append("])");
					}
					else
					{
						convertBuilder = new StringBuilder();
						convertBuilder.append("fields[").append(count).append("] = fieldConfigs[").append(configCount).append("].getDataPersister().javaToSqlArg(fieldConfigs[").append(configCount).append("], fields[").append(count).append("])");
					}
				}

				count++;
				assignStatements.add(sb.toString());
				if(convertBuilder != null)
					convertStatements.add(convertBuilder.toString());
			}

			configCount++;
		}

		CodeBlock.Builder builder = CodeBlock.builder();
		builder.addStatement("Object[] fields = new Object[$L]", assignStatements.size());

		for (String assignStatement : assignStatements)
		{
			builder.addStatement(assignStatement);
		}

		for (String convertStatement : convertStatements)
		{
			builder.addStatement(convertStatement);
		}

		builder.addStatement("return fields");

		returns.addCode(builder.build());
		configBuilder.addMethod(returns
						.build()
		);
	}

	private void makeCopyRows(List<DatabaseTableHolder> databaseTableHolders, MethodSpec.Builder methodBuilder, TypeElement element, String tableName, List<FieldTypeGen> fieldConfigs)
	{
		CodeBlock.Builder builder = CodeBlock.builder();
		int count = 0;
		for (FieldTypeGen fieldConfig : fieldConfigs)
		{
			makeCopyRow(databaseTableHolders, element, fieldConfig, builder, count++);
		}
		methodBuilder.addCode(builder.build());
	}


	private void makeCopyRow(List<DatabaseTableHolder> databaseTableHolders, TypeElement fieldElement, FieldTypeGen config, CodeBlock.Builder builder, int count)
	{
		DataType dataType = null;

		if (config.foreign)
		{
			for (DatabaseTableHolder databaseTableHolder : databaseTableHolders)
			{
				System.out.println("Find foreign: "+ databaseTableHolder.typeElement.getQualifiedName() + "/" + fieldElement.getQualifiedName());
				if(databaseTableHolder.typeElement.getQualifiedName().equals(fieldElement.getQualifiedName()))
				{
					for (FieldTypeGen fieldTypeGen : databaseTableHolder.fieldTypeGens)
					{
						if(fieldTypeGen.isId || fieldTypeGen.isGeneratedId)
						{
							dataType = fieldTypeGen.dataType;
						}
					}
				}
			}
		} else
		{
			dataType = config.dataType;
		}

		{
			String accessData = null;
			boolean checkNull = config.databaseField.canBeNull();

			switch (dataType)
			{
				case BOOLEAN:
				case BOOLEAN_OBJ:
					accessData = "results.getBoolean(" + count + ")";
					break;
				case DOUBLE:
				case DOUBLE_OBJ:
					accessData = "results.getDouble(" + count + ")";
					break;
				case FLOAT:
				case FLOAT_OBJ:
					accessData = "results.getFloat(" + count + ")";
					break;
				case INTEGER:
				case INTEGER_OBJ:
					accessData = "results.getInt(" + count + ")";
					break;
				case LONG:
				case LONG_OBJ:
					accessData = "results.getLong(" + count + ")";
					break;
				case SHORT:
				case SHORT_OBJ:
					accessData = "results.getShort(" + count + ")";
					break;
				case STRING:
					accessData = "results.getString(" + count + ")";
					checkNull = false;
					break;
				default:
					accessData = "(" + config.dataTypeClassname + ")fieldConfigs[" + count + "].getDataPersister().resultToJava(fieldConfigs[" + count + "], results, " + count + ")";
			}

			if (accessData != null)
			{
				StringBuilder sb = new StringBuilder();

				if(config.foreign)
				{
					ClassName className = ClassName.get(fieldElement);
					ClassName configName = ClassName.get(className.packageName(), Joiner.on('$').join(className.simpleNames()) + "$$Configuration");

					CodeBlock.Builder foreignBuilder = CodeBlock.builder();
					foreignBuilder.add("if(!results.wasNull(" + count + ")){");
					foreignBuilder.addStatement("$T __$N = $T.instance.createObject()", className, config.fieldName, configName);
					foreignBuilder.addStatement("$T.instance.assignId(__$N, $N)", configName, config.fieldName, accessData);


					if (config.useGetSet)
					{
						sb.append("data.set").append(StringUtils.capitalize(config.fieldName)).append("(")
								.append("__"+ config.fieldName).append(")");
					} else
					{
						sb.append("data.").append(config.fieldName).append(" = ")
								.append("__"+ config.fieldName);
					}

					foreignBuilder.addStatement(sb.toString());
					foreignBuilder.add("}");
					builder.add(foreignBuilder.build());

				}
				else
				{
					if(checkNull)
						sb.append("if(!results.wasNull("+ count+"))");

					if (config.useGetSet)
					{
						sb.append("data.set").append(StringUtils.capitalize(config.fieldName)).append("(")
								.append(accessData).append(")");
					} else
					{
						sb.append("data.").append(config.fieldName).append(" = ")
								.append(accessData);
					}

					builder.addStatement(sb.toString());
				}
			}
		}
	}

	private CodeBlock getFieldConfig(FieldTypeGen config, DatabaseField databaseField, String tableName, ClassName className)
	{
		CodeBlock.Builder builder = CodeBlock.builder()

				.addStatement("config = new $T( " +
								"new $T()," + //DatabaseType
								"$S," +
								"$S," +
								"$S," +
								"$L," + //isId
								"$L," +
								"$L," +
								(config.dataType == null ? "$L$L," : "$T.$L,") +
								"$L," +
								"$L," + //canBeNull
								"$S," +
								"$L," +
								"$L," +
								"$L," +
								"$L," + //uniqueIndex
								"$S," +
								"$S," +
								"$S," +
								"$L," +
								"$L," +
								"$L" +
								")",
						FieldType.class,
						SqliteAndroidDatabaseType.class,
						tableName,
						config.fieldName,
						config.columnName,
						config.isId,
						config.isGeneratedId,
						config.foreign,
						//TODO: NULL data type for foreign
						config.dataType == null ? "" : DataType.class,
						config.dataType == null ? "null" : config.dataType,
						databaseField.width(),
						databaseField.canBeNull(),
						StringUtils.trimToNull(databaseField.format()),
						databaseField.unique(),
						databaseField.uniqueCombo(),
						databaseField.index(),
						databaseField.uniqueIndex(),
						StringUtils.trimToNull(databaseField.indexName()),
						StringUtils.trimToNull(databaseField.uniqueIndexName()),
								config.defaultValue,
								databaseField.throwIfNull(),
								databaseField.version(),
								databaseField.readOnly()
						);

		return builder.build();
	}

	@Override
	public Set<String> getSupportedAnnotationTypes()
	{
		Set<String> annotations = new LinkedHashSet<String>();
		annotations.add(DatabaseTable.class.getCanonicalName());
		return annotations;
	}

	@Override
	public SourceVersion getSupportedSourceVersion()
	{
		return SourceVersion.latestSupported();
	}

	private void error(Element e, String msg, Object... args)
	{
		messager.printMessage(
				Diagnostic.Kind.ERROR,
				String.format(msg, args),
				e
		);
	}

	private static String extractTableName(TypeElement element)
	{
		DatabaseTable databaseTable = element.getAnnotation(DatabaseTable.class);
		String name;
		if (databaseTable != null && StringUtils.isNoneEmpty(databaseTable.tableName()))
		{
			name = databaseTable.tableName();
		}
		else
		{
			// if the name isn't specified, it is the class name lowercased
			name = element.getSimpleName().toString().toLowerCase();
		}
		return name;
	}

	private class ConfigureClassDefinitions
	{
		private List<DatabaseTableHolder> databaseTableHolders;
		private TypeElement element;
		private ClassName className;
		private ClassName idType;
		private ClassName configName;

		public ConfigureClassDefinitions(List<DatabaseTableHolder> databaseTableHolders, TypeElement element)
		{
			this.databaseTableHolders = databaseTableHolders;
			this.element = element;
		}

		public ClassName getClassName()
		{
			return className;
		}

		public ClassName getIdType()
		{
			return idType;
		}

		public ClassName getConfigName()
		{
			return configName;
		}

		public ConfigureClassDefinitions invoke()
		{
			DatabaseTableHolder myTableHolder = null;

			for (DatabaseTableHolder databaseTableHolder : databaseTableHolders)
			{
				if(databaseTableHolder.typeElement.getQualifiedName().equals(element.getQualifiedName()))
				{
					myTableHolder = databaseTableHolder;
					break;
				}
			}

			FieldTypeGen idFieldGen = null;
			for (FieldTypeGen fieldTypeGen : myTableHolder.fieldTypeGens)
			{
				if(fieldTypeGen.isId || fieldTypeGen.isGeneratedId)
				{
					idFieldGen = fieldTypeGen;
					break;
				}
			}
			className = ClassName.get(element);
			idType = ClassName.bestGuess(idFieldGen.dataTypeClassname);
			configName = ClassName.get(className.packageName(), Joiner.on('$').join(className.simpleNames()) + "$$Configuration");
			return this;
		}
	}
}
