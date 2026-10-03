package com.example.lms.routing;

import com.example.lms.domain.ConfigurationSetting;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutingSettingsPersistenceTest {
    EntityManagerFactory emf;
    RoutingSettingsService service;
    @BeforeEach void openSyntheticDatabase() {
        emf=new Configuration().addAnnotatedClass(ConfigurationSetting.class)
                .setProperty("hibernate.connection.driver_class","org.h2.Driver")
                .setProperty("hibernate.connection.url","jdbc:h2:mem:routing"+System.nanoTime()+";MODE=MariaDB;DB_CLOSE_DELAY=-1")
                .setProperty("hibernate.hbm2ddl.auto","create-drop").setProperty("hibernate.show_sql","false").buildSessionFactory();
        service=new RoutingSettingsService(new JpaTransactionManager(emf));
        ReflectionTestUtils.setField(service,"entityManager",SharedEntityManagerCreator.createSharedEntityManager(emf));
    }
    @AfterEach void close(){emf.close();}
    @Test void readDoesNotCreateRow() {
        var state=service.read();assertEquals(0,state.profileRevision());assertNull(state.profileHash());
        var em=emf.createEntityManager();
        try {assertNull(em.find(ConfigurationSetting.class,RoutingSettingsService.KEY));} finally {em.close();}
    }
    @Test void saveReadsBackCommittedRevisionAndHash() {
        var saved=service.save(RoutingProfile.parse(SettingsSnapshotCompatibilityTest.EMPTY),0,null);
        var read=service.read();assertEquals(1,saved.profileRevision());
        assertEquals(saved.profileHash(),read.profileHash());assertEquals(saved.profile(),read.profile());
    }
    @Test void staleRevisionAndHashDoNotOverwrite() {
        var first=service.save(RoutingProfile.parse(SettingsSnapshotCompatibilityTest.EMPTY),0,null);
        assertThrows(RoutingSettingsService.Conflict.class,()->service.save(first.profile(),0,null));
        assertThrows(RoutingSettingsService.Conflict.class,()->service.save(first.profile(),1,"wrong"));
        assertEquals(first,service.read());
    }
    @Test void twoInitialSavesAdoptOnlyOne() throws Exception {
        var executor=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        Callable<Boolean> call=()->{start.await();try{service.save(RoutingProfile.parse(SettingsSnapshotCompatibilityTest.EMPTY),0,null);return true;}
            catch(RoutingSettingsService.Conflict expected){return false;}};
        try {
            var a=executor.submit(call);var b=executor.submit(call);start.countDown();
            assertEquals(1,(a.get(10,TimeUnit.SECONDS)?1:0)+(b.get(10,TimeUnit.SECONDS)?1:0));
            assertEquals(1,service.read().profileRevision());
        } finally {executor.shutdownNow();}
    }
}
