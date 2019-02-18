package com.acme;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.config.RepositoryConfiguration;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.WebApplicationInitializer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.persistence.Entity;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.servlet.ServletContext;
import javax.servlet.ServletRegistration;
import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Properties;

import static java.lang.String.valueOf;

public class EmbeddedTomcatWebAppWithoutBoot {
  private static final int PORT = 8080;

  public static void main(String[] args) throws LifecycleException {
    String appBase = ".";
    Tomcat tomcat = new Tomcat();
    tomcat.setBaseDir(createTempDir());
    tomcat.setPort(PORT);
    tomcat.getHost().setAppBase(appBase);
    tomcat.addWebapp("", ".");
    tomcat.getConnector(); // Trigger the creation of the default connector
    tomcat.start();
    ClassLoader classLoader = findContext(tomcat).getLoader().getClassLoader();
    Thread.currentThread().setContextClassLoader(classLoader);
    tomcat.getServer().await();
  }

  private static Context findContext(Tomcat tomcat) {
    for (Container child : tomcat.getHost().findChildren()) {
      if (child instanceof Context) {
        return (Context) child;
      }
    }
    throw new IllegalStateException("The host does not contain a Context");
  }

  // based on AbstractEmbeddedServletContainerFactory
  private static String createTempDir() {
    try {
      File tempDir = File.createTempFile("tomcat.", "." + PORT);
      tempDir.delete();
      tempDir.mkdir();
      tempDir.deleteOnExit();
      return tempDir.getAbsolutePath();
    } catch (IOException ex) {
      throw new RuntimeException(
          "Unable to create tempDir. java.io.tmpdir is set to " + System.getProperty("java.io.tmpdir"),
          ex
      );
    }
  }
}


class MainWebAppInitializer implements WebApplicationInitializer {
  @Override
  public void onStartup(ServletContext servletContext) {
    AnnotationConfigWebApplicationContext rootContext = new AnnotationConfigWebApplicationContext();
    rootContext.register(DataAccessConfiguration.class, UserRepositoryImpl.class);
    servletContext.addListener(new ContextLoaderListener(rootContext));

    AnnotationConfigWebApplicationContext servletAppContext = new AnnotationConfigWebApplicationContext();
    servletAppContext.register(WebMvcConfiguration.class);
    DispatcherServlet dispatcherServlet = new DispatcherServlet(servletAppContext);
    ServletRegistration.Dynamic servletRegistration = servletContext.addServlet("dispatcher", dispatcherServlet);
    servletRegistration.setLoadOnStartup(1);
    servletRegistration.addMapping("/");
  }
}


@EnableWebMvc
@Configuration
@Import(UserResource.class)
class WebMvcConfiguration {}


@RestController
class UserResource {

  private final UserRepository userRepository;

  public UserResource(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @GetMapping
  ResponseEntity<List<User>> get() {
    return ResponseEntity.ok().body(userRepository.findAllUsers());
  }
}


@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
class UserRequest {
  private String name;
  private String authorityName;
}


@Configuration
@EnableTransactionManagement
class DataAccessConfiguration  {
  @Bean
  DataSource dataSource() {
    return new EmbeddedDatabaseBuilder()
        .setType(EmbeddedDatabaseType.H2)
        .setName("sample")
        .build();
  }

  @Bean
  LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
    LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();

    factoryBean.setDataSource(dataSource);

    HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
    factoryBean.setJpaVendorAdapter(vendorAdapter);
    factoryBean.setPackagesToScan(EmbeddedTomcatWebAppWithoutBoot.class.getPackage().getName());

    // set som extra properties hibernate
    Properties jpaProperties = new Properties();
    jpaProperties.setProperty("hibernate.show_sql", "true");
    jpaProperties.setProperty("hibernate.format_sql", "true");
    jpaProperties.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    jpaProperties.setProperty("hibernate.hbm2ddl.auto", "create-drop");
    factoryBean.setJpaProperties(jpaProperties);

    return factoryBean;
  }

  @Bean
  JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
    return new JpaTransactionManager(entityManagerFactory);
  }

  @Bean
  EntityManager entityManager(EntityManagerFactory entityManagerFactory) {
    return SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
  }

  @Bean
  PersistenceExceptionTranslationPostProcessor postProcessor() {
    return new PersistenceExceptionTranslationPostProcessor();
  }
}


interface UserRepository {
  List<User> findAllUsers();
}

@Repository
@Transactional
class UserRepositoryImpl implements UserRepository {
  private final EntityManager entityManager;

  public UserRepositoryImpl(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  public List<User> findAllUsers() {
    return entityManager.createQuery("from User", User.class)
        .getResultList();
  }
}

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
class User {
  @Id
  @GeneratedValue
  private int id;
  private String name;
}
