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
package org.burningwave.core.reflection;

import static org.burningwave.core.assembler.StaticComponentContainer.Cache;
import static org.burningwave.core.assembler.StaticComponentContainer.Classes;
import static org.burningwave.core.assembler.StaticComponentContainer.LowLevelObjectsHandler;
import static org.burningwave.core.assembler.StaticComponentContainer.Members;

import java.lang.reflect.Field;

import org.burningwave.core.classes.FieldCriteria;
import org.burningwave.core.function.ThrowingSupplier;

@SuppressWarnings("unchecked")
public class Fields extends MemberHelper<Field> {
	
	private Fields() {
		super();
	}
	
	public static Fields create() {
		return new Fields();
	}
	
	public <T> T get(Object target, String fieldName) {
		return ThrowingSupplier.get(() -> (T)findOneAndMakeItAccessible(target, fieldName).get(target));
	}	
	
	public <T> T getDirect(Object target, String fieldName) {
		return ThrowingSupplier.get(() -> (T)LowLevelObjectsHandler.getFieldValue(target, findOneAndMakeItAccessible(target, fieldName)));
	}
	
	public Field findOneAndMakeItAccessible(
		Object target,
		String fieldName
	) {	
		Class<?> targetClass = Classes.retrieveFrom(target);
		String cacheKey = getCacheKey(targetClass, "equals " + fieldName, (Object[])null);
		ClassLoader targetClassClassLoader = Classes.getClassLoader(targetClass);
		return Cache.uniqueKeyForField.getOrUploadIfAbsent(
			targetClassClassLoader, 
			cacheKey, 
			() -> {
				Field field = Members.findOne(
					FieldCriteria.forName(
						fieldName::equals
					),
					target				
				);
				field.setAccessible(true);
				return field;
			}
		);
	}

}
