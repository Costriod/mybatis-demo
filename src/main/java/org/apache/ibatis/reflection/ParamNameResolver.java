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

package org.apache.ibatis.reflection;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * 维护方法的参数名序号与参数名的映射关系
 * key为参数的序号，如第1个参数，第2个参数；value为参数名，RowBounds和ResultHandler两种类型参数不计算在内
 * <ul>
 * <li>如果UseActualParamName是false</li>
 * <li>aMethod(@Param("M") int a, @Param("N") int b) -&gt; {{0, "M"}, {1, "N"}}</li>
 * <li>aMethod(int a, int b) -&gt; {{0, "0"}, {1, "1"}}</li>
 * <li>aMethod(int a, RowBounds rb, int b) -&gt; {{0, "0"}, {2, "1"}}</li>
 * <li></li>
 * <li>如果UseActualParamName是true</li>
 * <li>aMethod(@Param("M") int a, @Param("N") int b) -&gt; {{0, "M"}, {1, "N"}}</li>
 * <li>aMethod(int a, int b) -&gt; {{0, "arg0"}, {1, "arg1"}}</li>
 * <li>aMethod(int a, RowBounds rb, int b) -&gt; {{0, "arg0"}, {2, "arg2"}}</li>
 * </ul>
 */
public class ParamNameResolver {

  private static final String GENERIC_NAME_PREFIX = "param";

  /**
   * key为参数的序号，如第1个参数，第2个参数；value为参数名
   * <p>
   * The key is the index and the value is the name of the parameter.<br />
   * The name is obtained from {@link Param} if specified. When {@link Param} is not specified,
   * the parameter index is used. Note that this index could be different from the actual index
   * when the method has special parameters (i.e. {@link RowBounds} or {@link ResultHandler}).
   * </p>
   * <ul>
   * <li>如果UseActualParamName是false</li>
   * <li>aMethod(@Param("M") int a, @Param("N") int b) -&gt; {{0, "M"}, {1, "N"}}</li>
   * <li>aMethod(int a, int b) -&gt; {{0, "0"}, {1, "1"}}</li>
   * <li>aMethod(int a, RowBounds rb, int b) -&gt; {{0, "0"}, {2, "1"}}</li>
   * <li></li>
   * <li>如果UseActualParamName是true</li>
   * <li>aMethod(@Param("M") int a, @Param("N") int b) -&gt; {{0, "M"}, {1, "N"}}</li>
   * <li>aMethod(int a, int b) -&gt; {{0, "arg0"}, {1, "arg1"}}</li>
   * <li>aMethod(int a, RowBounds rb, int b) -&gt; {{0, "arg0"}, {2, "arg2"}}</li>
   * </ul>
   */
  private final SortedMap<Integer, String> names;
  
  /**至少有一个参数被Param注解修饰则为true*/
  private boolean hasParamAnnotation;

  public ParamNameResolver(Configuration config, Method method) {
    final Class<?>[] paramTypes = method.getParameterTypes();
    //method.getParameterAnnotations()返回一个二维数组，表示的是一个方法中参数前方的注解
    //如func(@Anno1()@Anno2()int id, @Anno3()String name)，则该方法的Annotation[][] annos = {{Anno1,Anno2},{Anno3}}
    final Annotation[][] paramAnnotations = method.getParameterAnnotations();
    final SortedMap<Integer, String> map = new TreeMap<Integer, String>();
    int paramCount = paramAnnotations.length;
    //如果paramCount>0
    for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
      if (isSpecialParameter(paramTypes[paramIndex])) {//如果第i个参数类型是RowBounds或ResultHandler
        // skip special parameters
        continue;
      }
      String name = null;
      for (Annotation annotation : paramAnnotations[paramIndex]) {//遍历第i个参数的注解（参数可能有多个注解）
        if (annotation instanceof Param) {//如果某个注解是Param类型
          hasParamAnnotation = true;
          name = ((Param) annotation).value();
          break;
        }
      }
      if (name == null) {//如果没有param注解
        // @Param was not specified.
        if (config.isUseActualParamName()) {
          name = getActualParamName(method, paramIndex);//返回method第i个参数的参数名
        }
        if (name == null) {//如果还是为空，那就只能是0,1,2,3,4这种编号作为命名了
          // use the parameter index as the name ("0", "1", ...)
          // gcode issue #71
          name = String.valueOf(map.size());
        }
      }
      map.put(paramIndex, name);
    }
    names = Collections.unmodifiableSortedMap(map);
  }

  private String getActualParamName(Method method, int paramIndex) {
    if (Jdk.parameterExists) {
      return ParamNameUtil.getParamNames(method).get(paramIndex);
    }
    return null;
  }

  private static boolean isSpecialParameter(Class<?> clazz) {
    return RowBounds.class.isAssignableFrom(clazz) || ResultHandler.class.isAssignableFrom(clazz);
  }

  /**
   * Returns parameter names referenced by SQL providers.
   */
  public String[] getNames() {
    return names.values().toArray(new String[0]);
  }

  /**
   * <p>
   * A single non-special parameter is returned without a name.<br />
   * Multiple parameters are named using the naming rule.<br />
   * In addition to the default names, this method also adds the generic names (param1, param2,
   * ...).
   * </p>
   */
  public Object getNamedParams(Object[] args) {
    final int paramCount = names.size();
    if (args == null || paramCount == 0) {//如果参数为空或names为空
      return null;
    } else if (!hasParamAnnotation && paramCount == 1) {//如果没有Param注解参数
      return args[names.firstKey()];//names.firstKey()是红黑树的最小节点，也就是根节点，其实这里返回的是方法最前面的参数（不包括RowBounds和ResultHandler两种类型参数）的序号
    } else {//否则返回ParamMap，key为方法参数名，value为args[n]
      final Map<String, Object> param = new ParamMap<Object>();
      int i = 0;
      for (Map.Entry<Integer, String> entry : names.entrySet()) {
        param.put(entry.getValue(), args[entry.getKey()]);
        // add generic param names (param1, param2, ...)
        final String genericParamName = GENERIC_NAME_PREFIX + String.valueOf(i + 1);
        // ensure not to overwrite parameter named with @Param
        if (!names.containsValue(genericParamName)) {
          param.put(genericParamName, args[entry.getKey()]);
        }
        i++;
      }
      return param;
    }
  }
}
