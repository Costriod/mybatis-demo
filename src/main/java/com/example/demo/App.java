package com.example.demo;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

public class App {

    public static void main(String[] args) throws IOException {
        String resource = "config.xml";
        InputStream inputStream = Resources.getResourceAsStream(resource);
        //返回{@link DefaultSqlSessionFactory}对象
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
        //返回{@link DefaultSqlSession}对象
        SqlSession session = sqlSessionFactory.openSession();
        try {
            UserMapper mapper = session.getMapper(UserMapper.class);
            User user = new User();
            user.setId(3);
            user.setUsername("test");
            user.setCreateTime(new Date());
            user.setEmail("test@abc.com");
            user.setPassword("password");
            //mapper.insert(user);
            mapper.deleteByPrimaryKey(1);
            mapper.deleteByPrimaryKey(2);
            mapper.deleteByPrimaryKey(3);
            session.commit();
        } finally {
          session.close();
        }
    }

}
