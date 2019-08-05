# mybatis-demo based on mybatis v3.4.5 附带源码注释
### 注意mybatis v3.4.0到3.4.6版本二级缓存对象底层用的是HashMap，也没有任何加锁措施，开启二级缓存后在事务提交写入二级缓存的过程中可能造成死循环
### 注意如果使用mybatis二级缓存，则POJO类需要实现Serializable，否则会抛异常，当然也可以自定义实现缓存系统，以避免这种问题
```
1.二级缓存对象是全局对象，每个namespace下面的所有MappedStatement共享同一个二级缓存对象

2.mybatis首先解析cache-ref标签(标签内容为另一个namespace的全称)，会设置当前namespace的二级缓存为cache-ref对应namespace的缓存；接着解析cache标签，然后会重新设置二级缓存对象；言外之意就是cache会覆盖cache-ref的配置

3.如果开启了二级缓存，在SqlSession close或commit的时候会将查询数据缓存到二级缓存里面

4.如果开启了二级缓存，每次insert/update/delete则会清除当前session前面已经缓存起来的entriesToAddOnCommit数据(这部分数据还未写入到二级缓存里面，需要等到SqlSession close或commit的时候才会写入到二级缓存里面)

5.如果开启了二级缓存，查询的时候先从二级缓存里面找，如果没找到则从一级缓存里面找，如果还没找到则执行查询，并将查询结果存入一级缓存，同时也会把这次的查询数据缓存在entriesToAddOnCommit

6.默认configuration的LocalCacheScope为SESSION，表示同一个Session下面查询的数据都放到一级缓存里面，如果LocalCacheScope为STATEMENT则表示一级缓存不保存数据(保存了数据都会自动清除)

7.在创建DefaultSqlSession之前会创建一个Executor对象，Executor对象是CachingExecutor类型，接着全局configuration的interceptorChain会对这个Executor执行一次pluginAll操作；

接着会创建StatementHandler，这个StatementHandler本质上是RoutingStatementHandler类型，RoutingStatementHandler内部根据StatementType维护了一个delegate(SimpleStatementHandler/PreparedStatementHandler/CallableStatementHandler)，在SimpleStatementHandler/PreparedStatementHandler/CallableStatementHandler构造函数执行过程中会生成ParameterHandler和ResultSetHandler，ParameterHandler和ResultSetHandler也需要做一次interceptorChain.pluginAll；

创建好StatementHandler之后还会执行一次configuration的interceptorChain.pluginAll；

最后执行statement，如PreparedStatementHandler还需要进行预编译
所以可以自定义一些拦截器实现需要的效果，如分页拦截器

8.mybatis默认是客户端分页，也就是查询出所有符合条件的数据，然后在客户端内存里面分页，会带来很大的网络IO，所以需要我们在服务器端分页，然后再返回给客户端，一般是通过limit(mysql)关键字实现

9.mybatis对#{}的内容安全设置值，防止sql注入，而对于${}的内容则有sql注入风险
#{} 这种是把sql组装成"select * from table where id = ?"这种格式，然后让数据库预编译，最后设置参数执行
${} 这种是把参数值和sql一起组装成"select * from table where id = 1"这种格式，然后让数据库预编译，最后执行时也不需要重新设置参数了，所以会造成sql注入
```

- mybatis分页拦截器实现

原理configuration有一个interceptorChain，分别在以下3种情况下会使用拦截器链对一些对象做一些后置处理工作

- SqlSession创建Executor之后会执行一次interceptorChain.pluginAll(executor)
- configuration创建RoutingStatementHandler，执行构造函数过程中会初始化delegate对象，delegate对象初始化的构造函数会创建parameterHandler和resultSetHandler，创建parameterHandler和resultSetHandler的过程中都会执行一次interceptorChain.pluginAll(parameterHandler)和interceptorChain.pluginAll(resultSetHandler)
- configuration创建RoutingStatementHandler之后会执行一次interceptorChain.pluginAll(statementHandler)，此时statement还没发送到数据库服务器执行预编译

所以可以针对这几个环节做一些拦截操作，mybatis拦截器就是在这里为专用的几个类对象生成一个动态代理，然后执行一些自定义操作，这里一般ResultSetHandler的rowBounds都是默认值，但不排除有些人使用了RowBounds实现内存分页（内存分页需要查询出所有数据，然后在内存中一行一行翻页获取目标内容，性能太低，一般都是通过limit在服务端分页），所以这里强制将rowBounds设为默认值，然后利用limit在服务端分页

```java
import java.sql.Connection;
import java.sql.Statement;
import java.util.Properties;

import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.executor.statement.PreparedStatementHandler;
import org.apache.ibatis.executor.statement.RoutingStatementHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.RowBounds;

@Intercepts({ @Signature(type = StatementHandler.class, method = "prepare", args = { Connection.class, Integer.class }),
		@Signature(type = ResultSetHandler.class, method = "handleResultSets", args = { Statement.class }) })
public class PageInterceptor implements Interceptor {

	public Object intercept(Invocation invocation) throws Throwable {
		if (invocation.getTarget() instanceof StatementHandler) {
			RoutingStatementHandler statement = (RoutingStatementHandler) invocation.getTarget();
			PreparedStatementHandler handler = (PreparedStatementHandler) ReflectUtil.getFieldValue(statement,"delegate");//通过反射工具类获取值，反射工具类百度一大堆
			RowBounds rowBounds = (RowBounds) ReflectUtil.getFieldValue(handler, "rowBounds");
			if (rowBounds.getLimit() > 0 && rowBounds.getLimit() < RowBounds.NO_ROW_LIMIT) {
				BoundSql boundSql = statement.getBoundSql();
				String sql = boundSql.getSql();
				sql = getLimitString(sql, rowBounds.getOffset(), rowBounds.getLimit());
				ReflectUtil.setFieldValue(boundSql, "sql", sql);
			}
			return invocation.proceed();
		} else if (invocation.getTarget() instanceof ResultSetHandler) {
			DefaultResultSetHandler resultSet = (DefaultResultSetHandler) invocation.getTarget();
			RowBounds rowBounds = (RowBounds) ReflectUtil.getFieldValue(resultSet, "rowBounds");
			if (rowBounds.getLimit() > 0 && rowBounds.getLimit() < RowBounds.NO_ROW_LIMIT) {
				ReflectUtil.setFieldValue(resultSet, "rowBounds", RowBounds.DEFAULT);
			}
			return invocation.proceed();
		} else {
			return invocation.proceed();
		}
	}

	public Object plugin(Object target) {
		return Plugin.wrap(target, this);
	}
	
	public void setProperties(Properties arg0) {
		
	}

	/**
	 * 组装分页sql语句, 各个数据库分页语句规范不一样, 如MySQL的分页是这样的：select * from A limit 0,20(limit 0,20表示从第1页开始，每页20条记录)
	 * offset参数是页偏移量, 如你要查询第1页, 每页20条数据：getLimitString(String sql, int 1, int 20), sql就是你的查询语句
	 */
	public String getLimitString(String sql, int offset, int limit) {
		sql = sql.trim();
		int offset = offset - 1;
		StringBuffer statement = new StringBuffer(sql.length() + 100);
		statement.append(sql);
		/**此处limit左右两边的空格不要删掉了，这个空格是用来和sql组装成分页的sql*/
		statement.append(" limit " + offset + "," + limit);
		return statement.toString();
	}
}
```
