package io.dakich.spring.hibernate.custom.repository;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

import io.dakich.spring.hibernate.custom.domain.Role;
import io.dakich.spring.hibernate.custom.domain.User;
import io.dakich.spring.hibernate.custom.repository.config.TestConfig;
import io.dakich.spring.hibernate.custom.repository.sample.RoleRepository;
import io.dakich.spring.hibernate.custom.repository.sample.UserRepository;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = TestConfig.class)
@ExtendWith(SpringExtension.class)
public abstract class AbstractTransaction {
  private static final Logger LOG = LoggerFactory.getLogger(AbstractTransaction.class);
  @Autowired
  private EntityManager entityManager;
  @Autowired
  private UserRepository userRepository;

  @Autowired
  private RoleRepository roleRepository;

  @Autowired
  private AsyncTaskExecutor taskExecutor;
  @Autowired
  private DataSource dataSource;

  @Autowired
  private PlatformTransactionManager transactionManager;

  private TransactionTemplate transactionTemplate;


  @BeforeEach
  void setUp() {
    transactionTemplate = new TransactionTemplate(transactionManager);
  }

  @Test
  void testSaveParallel() {
    Assertions.assertTrue(childExpectedCacheStrategy()==CacheManager.getCacheStrategy());
    final Role role = roleRepository.save(new Role("test"));
    final List<Integer> list = new ArrayList<>();
    transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_UNCOMMITTED);
    transactionTemplate.executeWithoutResult(res -> {
      userRepository.save(
          new User("test" + 6, "test" + 6, "test" + 6, role));
      userRepository.save(
          new User("test" + 7, "test" + 7, "test" + 7, role));
      userRepository.save(
          new User("test" + 8, "test" + 8, "test" + 8, role));
    });

    final List<User> all = userRepository.findAll();
    all.forEach(a -> LOG.warn("DATA:{}", a.isActive()));
    Assertions.assertTrue(userRepository.findById(1).get().isActive());
    Assertions.assertTrue(userRepository.findById(2).get().isActive());
    Assertions.assertTrue(userRepository.findById(3).get().isActive());
    transactionTemplate.executeWithoutResult(res -> {
      final List<User> all6 = userRepository.findAll();
      final Future upd6 = taskExecutor.submit(
          () -> execOutOfTrans("UPDATE SD_User set ACTIVE=FALSE where id=1"));
      final Future upd7 = taskExecutor.submit(
          () -> execOutOfTrans("UPDATE SD_User set ACTIVE=FALSE where id=2"));
      await().atMost(6, SECONDS).until(() -> upd6.isDone() && upd7.isDone());
      final List<User> all8 = userRepository.findAll();
      final List<User> all7 = userRepository.findAll(Sort.by(Order.asc("id")));
      LOG.warn(String.valueOf(list.size()));
      all7.forEach(a -> LOG.warn("DATA 7:{}", a.isActive()));

      Assertions.assertFalse(userRepository.findById(1).get().isActive());
      Assertions.assertFalse(userRepository.findById(2).get().isActive());
      Assertions.assertTrue(userRepository.findById(3).get().isActive());
      Assertions.assertFalse(all7.get(0).isActive());
      Assertions.assertFalse(all7.get(1).isActive());
      Assertions.assertTrue(all7.get(2).isActive());
    });

  }


  void execOutOfTrans(String sql) {
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(false);
      SQLException savedException = null;
      try {
        final int i = conn.prepareStatement(sql).executeUpdate();
        // Do things with connection in transaction here...
        conn.commit();
      } catch (SQLException ex) {
        savedException = ex;
        conn.rollback();
      } finally {
        conn.setAutoCommit(true);
        if (savedException != null) {
          throw savedException;
        }
      }
    } catch (SQLException ex6) {
      throw new RuntimeException(ex6);
    }
  }

  private Specification<User> nameLike(String name) {
    return (root, query, criteriaBuilder)
        -> criteriaBuilder.like(root.get("lastname"), "%" + name + "%");
  }

  protected abstract CACHE_STRATEGY childExpectedCacheStrategy();
}
