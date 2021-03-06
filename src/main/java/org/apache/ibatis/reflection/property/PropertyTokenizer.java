/**
 *    Copyright 2009-2017 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.reflection.property;

import java.util.Iterator;

/**
 * @author Clinton Begin
 * 类实现了Iterator&lt;PropertyTokenizer&gt;接口，传入一个属性名，解析属性名信息
 * <p>1.如果传入属性名有“.”，如a.b，则name=a, children=b, indexedName=name=a, index=null</p>
 * <p>2.如果传入属性名没有“.”，如abc，则name=abc, children=null, indexedName=name=abc, index=null</p>
 * <p>3.如果传入属性名没有“[”，如abc[bcd]，则name=abc, children=null, indexedName=abc[bcd], index=bcd</p>
 * 
 * hasNext为true的唯一条件是属性名带有“.”
 * <pre>@Override
 * public boolean hasNext() {
 *   return children != null;
 * }</pre>
 */
public class PropertyTokenizer implements Iterator<PropertyTokenizer> {
  private String name;
  private final String indexedName;
  private String index;
  private final String children;
  
  
  public PropertyTokenizer(String fullname) {
	// 对参数进行第一次处理，通过“.”分隔符将propertyName分作两部分
    int delim = fullname.indexOf('.');
    if (delim > -1) {
      name = fullname.substring(0, delim);
      children = fullname.substring(delim + 1);
    } else {
      name = fullname;
      children = null;
    }
    indexedName = name;
    // 对name进行二次处理,去除“[...]”，并将方括号内的内容赋给index属性，如果name属性中包含“[]”的话
    delim = name.indexOf('[');
    if (delim > -1) {
      index = name.substring(delim + 1, name.length() - 1);
      name = name.substring(0, delim);
    }
  }

  public String getName() {
    return name;
  }

  public String getIndex() {
    return index;
  }

  public String getIndexedName() {
    return indexedName;
  }

  public String getChildren() {
    return children;
  }

  /**
   * hasNext为true的唯一条件是属性名带有“.”
   */
  @Override
  public boolean hasNext() {
    return children != null;
  }

  @Override
  public PropertyTokenizer next() {
    return new PropertyTokenizer(children);
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException("Remove is not supported, as it has no meaning in the context of properties.");
  }
}
