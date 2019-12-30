package org.burningwave.core.classes;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.function.Supplier;

import org.burningwave.Throwables;
import org.burningwave.core.Component;
import org.burningwave.core.assembler.ComponentSupplier;
import org.burningwave.core.classes.JavaMemoryCompiler.MemoryFileObject;
import org.burningwave.core.classes.hunter.ClassPathHunter;
import org.burningwave.core.io.PathHelper;


public class ClassFactory implements Component {
	public static String CLASS_REPOSITORIES = "classFactory.classRepositories";
	
	private ClassHelper classHelper;
	private ClassPathHunter classPathHunter;
	private PathHelper pathHelper;
	private Supplier<MemoryClassLoader> memoryClassLoaderSupplier;
	private MemoryClassLoader memoryClassLoader;
	private JavaMemoryCompiler javaMemoryCompiler;
	private CodeGenerator codeGeneratorForPojo;
	private CodeGenerator codeGeneratorForConsumer;
	private CodeGenerator codeGeneratorForFunction;
	private CodeGenerator codeGeneratorForPredicate;
	private CodeGenerator codeGeneratorForExecutor;
	
	private ClassFactory(
		ClassHelper classHelper,
		ClassPathHunter classPathHunter,
		Supplier<MemoryClassLoader> memoryClassLoaderSupplier,
		JavaMemoryCompiler javaMemoryCompiler,
		PathHelper pathHelper,
		CodeGenerator.ForPojo codeGeneratorForPojo,
		CodeGenerator.ForFunction codeGeneratorForFunction,
		CodeGenerator.ForConsumer codeGeneratorForConsumer,
		CodeGenerator.ForPredicate codeGeneratorForPredicate,
		CodeGenerator.ForCodeExecutor codeGeneratorForExecutor
	) {	
		this.classHelper = classHelper;
		this.classPathHunter = classPathHunter;
		this.memoryClassLoaderSupplier = memoryClassLoaderSupplier;
		this.javaMemoryCompiler = javaMemoryCompiler;
		this.pathHelper = pathHelper;
		this.codeGeneratorForPojo = codeGeneratorForPojo;
		this.codeGeneratorForConsumer = codeGeneratorForConsumer;
		this.codeGeneratorForFunction = codeGeneratorForFunction;
		this.codeGeneratorForPredicate = codeGeneratorForPredicate;
		this.codeGeneratorForExecutor = codeGeneratorForExecutor;
	}
	
	public static ClassFactory create(
		ClassHelper classHelper,
		ClassPathHunter classPathHunter,
		Supplier<MemoryClassLoader> memoryClassLoaderSupplier,
		JavaMemoryCompiler javaMemoryCompiler,
		PathHelper pathHelper,
		CodeGenerator.ForPojo codeGeneratorForPojo,
		CodeGenerator.ForFunction codeGeneratorForFunction,
		CodeGenerator.ForConsumer codeGeneratorForConsumer,
		CodeGenerator.ForPredicate codeGeneratorForPredicate,
		CodeGenerator.ForCodeExecutor codeGeneratorForExecutor
	) {
		return new ClassFactory(
			classHelper, classPathHunter, memoryClassLoaderSupplier, 
			javaMemoryCompiler, pathHelper, codeGeneratorForPojo, 
			codeGeneratorForFunction, codeGeneratorForConsumer, codeGeneratorForPredicate, codeGeneratorForExecutor
		);
	}
	
	private MemoryClassLoader getMemoryClassLoader() {
		return memoryClassLoader != null ?
			memoryClassLoader :
			(memoryClassLoader = memoryClassLoaderSupplier.get());	

	}
	
	private Class<?> getFromMemoryClassLoader(String className) {
		Class<?> cls = null;
		try {
			cls = Class.forName(className, false, getMemoryClassLoader() );
		} catch (ClassNotFoundException e) {
			logInfo(className + " not found in " + getMemoryClassLoader() );
		}
		return cls;
	}	
	
	public Collection<MemoryFileObject> build(String classCode) {
		logInfo("Try to compile virtual class:\n\n" + classCode +"\n");
		return javaMemoryCompiler.compile(
			Arrays.asList(classCode), 
			pathHelper.getMainClassPaths(),
			pathHelper.getClassPaths(CLASS_REPOSITORIES)
		);
	}
	
	private Class<?> buildAndUploadToMemoryClassLoader(String classCode) {
		String className = classHelper.extractClassName(classCode);
		Collection<MemoryFileObject> compiledFiles = build(classCode);
		logInfo("Virtual class " + className + " succesfully created");
		if (!compiledFiles.isEmpty()) {
			Iterator<MemoryFileObject> compiledFilesIterator = compiledFiles.iterator();
			while (compiledFilesIterator.hasNext()) {
				try (MemoryFileObject memoryFileObject = compiledFilesIterator.next()) {
					memoryClassLoader.addCompiledClass(
						memoryFileObject.getName(), memoryFileObject.toByteBuffer()
					);
				}
			}
		}
		try {
			return getMemoryClassLoader().loadClass(className);
		} catch (ClassNotFoundException e) {
			throw Throwables.toRuntimeException(e);
		}
	}
	
	
	public Class<?> getOrBuild(String classCode) {
		String className = classHelper.extractClassName(classCode);
		Class<?> toRet = getFromMemoryClassLoader(className);
		if (toRet == null) {
			toRet = buildAndUploadToMemoryClassLoader(classCode);
		}
		return toRet;
	}	
	
	public Class<?> getOrBuildPojoSubType(String className, Class<?>... superClasses) {
		return getOrBuild(codeGeneratorForPojo.generate(className, superClasses));
	}
	
	public Class<?> getOrBuildFunctionSubType(int parametersLength) {
		return getOrBuild(codeGeneratorForFunction.generate(parametersLength));
	}
	
	public Class<?> getOrBuildConsumerSubType(int parametersLength) {
		return getOrBuild(codeGeneratorForConsumer.generate(parametersLength));
	}
	
	public Class<?> getOrBuildPredicateSubType(int parametersLength) {
		return getOrBuild(codeGeneratorForPredicate.generate(parametersLength));
	}
	
	public Class<?> getOrBuildCodeExecutorSubType(String imports, String className, String supplierCode,
			Class<?> returnedClass, ComponentSupplier componentSupplier
	) {
		return getOrBuildCodeExecutorSubType(imports, className, supplierCode, returnedClass, componentSupplier, getMemoryClassLoader());
	}
	
	
	public Class<?> getOrBuildCodeExecutorSubType(String imports, String className, String supplierCode,
			Class<?> returnedClass, ComponentSupplier componentSupplier, MemoryClassLoader memoryClassLoader
	) {
		String classCode = codeGeneratorForExecutor.generate(
			imports, className, supplierCode, returnedClass
		);
		Collection<MemoryFileObject> compiledFiles = JavaMemoryCompiler.create(pathHelper, classHelper, classPathHunter).compile(
			Arrays.asList(classCode), 
			pathHelper.getMainClassPaths(),
			pathHelper.getClassPaths(ClassFactory.CLASS_REPOSITORIES)
		);
		if (!compiledFiles.isEmpty()) {
			Iterator<MemoryFileObject> compiledFilesIterator = compiledFiles.iterator();
			while(compiledFilesIterator.hasNext()) {
				try(MemoryFileObject memoryFileObject = compiledFilesIterator.next()) {
					memoryClassLoader.addCompiledClass(
						memoryFileObject.getName(), memoryFileObject.toByteBuffer()
					);
				}
			}
		}
		try {
			return memoryClassLoader.loadClass(classHelper.extractClassName(classCode));
		} catch (ClassNotFoundException exc) {
			throw Throwables.toRuntimeException(exc);
		}
	}
}
