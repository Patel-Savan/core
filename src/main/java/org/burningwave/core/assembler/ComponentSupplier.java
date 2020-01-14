/*
 * This file is part of Burningwave Core.
 *
 * Author: Roberto Gentili
 *
 * Hosted at: https://github.com/burningwave/core
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Roberto Gentili
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.burningwave.core.assembler;

import java.util.function.Supplier;

import org.burningwave.core.Component;
import org.burningwave.core.classes.ClassFactory;
import org.burningwave.core.classes.ClassHelper;
import org.burningwave.core.classes.CodeGenerator;
import org.burningwave.core.classes.ConstructorHelper;
import org.burningwave.core.classes.FieldHelper;
import org.burningwave.core.classes.JavaMemoryCompiler;
import org.burningwave.core.classes.MemberFinder;
import org.burningwave.core.classes.MemoryClassLoader;
import org.burningwave.core.classes.MethodHelper;
import org.burningwave.core.classes.hunter.ByteCodeHunter;
import org.burningwave.core.classes.hunter.ClassHunter;
import org.burningwave.core.classes.hunter.ClassPathHunter;
import org.burningwave.core.classes.hunter.FSIClassHunter;
import org.burningwave.core.classes.hunter.FSIClassPathHunter;
import org.burningwave.core.concurrent.ConcurrentHelper;
import org.burningwave.core.io.FileSystemHelper;
import org.burningwave.core.io.PathHelper;
import org.burningwave.core.io.StreamHelper;
import org.burningwave.core.iterable.IterableObjectHelper;
import org.burningwave.core.reflection.CallerRetriever;
import org.burningwave.core.reflection.ConsumerBinder;
import org.burningwave.core.reflection.FunctionBinder;
import org.burningwave.core.reflection.FunctionalInterfaceFactory;
import org.burningwave.core.reflection.LowLevelObjectsHandler;
import org.burningwave.core.reflection.PropertyAccessor;
import org.burningwave.core.reflection.RunnableBinder;
import org.burningwave.core.reflection.SupplierBinder;

public interface ComponentSupplier extends Component {
	
	public<T extends Component> T getOrCreate(Class<T> componentType, Supplier<T> componentSupplier);

	public ConstructorHelper getConstructorHelper();

	public MethodHelper getMethodHelper();

	public FieldHelper getFieldHelper();

	public MemberFinder getMemberFinder();

	public MemoryClassLoader getMemoryClassLoader();

	public ClassFactory getClassFactory();
	
	public ClassHelper getClassHelper();

	public JavaMemoryCompiler getJavaMemoryCompiler();

	public CodeGenerator.ForConsumer getCodeGeneratorForConsumer();

	public CodeGenerator.ForFunction getCodeGeneratorForFunction();
	
	public CodeGenerator.ForPredicate getCodeGeneratorForPredicate();
	
	public CodeGenerator.ForPojo getCodeGeneratorForPojo();
	
	public CodeGenerator.ForCodeExecutor getCodeGeneratorForCodeExecutor();
	
	public ByteCodeHunter getByteCodeHunter();
	
	public ClassPathHunter getClassPathHunter();
	
	public FSIClassPathHunter getFSIClassPathHunter();
	
	public ClassHunter getClassHunter();
	
	public FSIClassHunter getFSIClassHunter();

	public PropertyAccessor.ByFieldOrByMethod getByFieldOrByMethodPropertyAccessor();
	
	public PropertyAccessor.ByMethodOrByField getByMethodOrByFieldPropertyAccessor();

	public RunnableBinder getRunnableBinder();

	public SupplierBinder getSupplierBinder();

	public ConsumerBinder getConsumerBinder();

	public FunctionBinder getFunctionBinder();

	public FunctionalInterfaceFactory getFunctionalInterfaceFactory();

	public CallerRetriever getLambdaCallerRetriever();

	public PathHelper getPathHelper();

	public StreamHelper getStreamHelper();

	public FileSystemHelper getFileSystemHelper();
	
	public ConcurrentHelper getConcurrentHelper();
	
	public IterableObjectHelper getIterableObjectHelper();
	
	public LowLevelObjectsHandler getLowLevelObjectsHandler();
	
	public ComponentSupplier clear();
	
	public static ComponentSupplier getInstance() {
		return ComponentContainer.getInstance();
	}
}