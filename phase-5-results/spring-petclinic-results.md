# spring-petclinic

## Description
Spring PetClinic - Spring Boot web app

## Test Results
- Tests run: 50
- Failures: 0
- Errors: 0
- Skipped: 0
- Execution time: 22s
- Exit code: 0

## Test Output
```
2026-04-21T15:35:20.411+02:00  INFO 93616 --- [           main] o.s.s.p.service.ClinicServiceTests       : No active profile set, falling back to 1 default profile: "default"
2026-04-21T15:35:20.480+02:00  INFO 93616 --- [           main] .s.d.r.c.RepositoryConfigurationDelegate : Bootstrapping Spring Data JPA repositories in DEFAULT mode.
2026-04-21T15:35:20.505+02:00  INFO 93616 --- [           main] .s.d.r.c.RepositoryConfigurationDelegate : Finished Spring Data repository scanning in 21 ms. Found 3 JPA repository interfaces.
2026-04-21T15:35:20.617+02:00  INFO 93616 --- [           main] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Starting...
2026-04-21T15:35:20.806+02:00  INFO 93616 --- [           main] com.zaxxer.hikari.pool.HikariPool        : HikariPool-1 - Added connection conn0: url=jdbc:h2:mem:ef044f2a-f144-4194-91cc-43ee81a76f0a user=SA
2026-04-21T15:35:20.806+02:00  INFO 93616 --- [           main] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Start completed.
2026-04-21T15:35:20.922+02:00  INFO 93616 --- [           main] org.hibernate.orm.jpa                    : HHH008540: Processing PersistenceUnitInfo [name: default]
2026-04-21T15:35:20.963+02:00  INFO 93616 --- [           main] org.hibernate.orm.core                   : HHH000001: Hibernate ORM core version 7.2.4.Final
2026-04-21T15:35:21.140+02:00  INFO 93616 --- [           main] o.s.o.j.p.SpringPersistenceUnitInfo      : No LoadTimeWeaver setup: ignoring JPA class transformer
2026-04-21T15:35:21.206+02:00  INFO 93616 --- [           main] org.hibernate.orm.connections.pooling    : HHH10001005: Database info:
	Database JDBC URL [jdbc:h2:mem:ef044f2a-f144-4194-91cc-43ee81a76f0a]
	Database driver: H2 JDBC Driver
	Database dialect: H2Dialect
	Database version: 2.4.240
	Default catalog/schema: EF044F2A-F144-4194-91CC-43EE81A76F0A/PUBLIC
	Autocommit mode: undefined/unknown
	Isolation level: READ_COMMITTED [default READ_COMMITTED]
	JDBC fetch size: 100
	Pool: DataSourceConnectionProvider
	Minimum pool size: undefined/unknown
	Maximum pool size: undefined/unknown
2026-04-21T15:35:21.836+02:00  INFO 93616 --- [           main] org.hibernate.orm.core                   : HHH000489: No JTA platform available (set 'hibernate.transaction.jta.platform' to enable JTA platform integration)
2026-04-21T15:35:21.840+02:00  INFO 93616 --- [           main] j.LocalContainerEntityManagerFactoryBean : Initialized JPA EntityManagerFactory for persistence unit 'default'
2026-04-21T15:35:21.881+02:00  INFO 93616 --- [           main] o.s.d.j.r.query.QueryEnhancerFactories   : Hibernate is in classpath; If applicable, HQL parser will be used.
2026-04-21T15:35:22.253+02:00  INFO 93616 --- [           main] o.s.s.p.service.ClinicServiceTests       : Started ClinicServiceTests in 1.853 seconds (process running for 8.747)
Hibernate: select v1_0.id,v1_0.first_name,v1_0.last_name from vets v1_0
Hibernate: select s1_0.vet_id,s1_1.id,s1_1.name from vet_specialties s1_0 join specialties s1_1 on s1_1.id=s1_0.specialty_id where s1_0.vet_id in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
WARNING: Final field pets in class org.springframework.samples.petclinic.owner.Owner has been mutated reflectively by class org.hibernate.property.access.spi.SetterFieldImpl in unnamed module @2eee3069 (file:/Users/i560383_1/.m2/repository/org/hibernate/orm/hibernate-core/7.2.4.Final/hibernate-core-7.2.4.Final.jar)
WARNING: Use --enable-final-field-mutation=ALL-UNNAMED to avoid a warning
WARNING: Mutating final fields will be blocked in a future release unless final field mutation is enabled
Hibernate: select o1_0.id,o1_0.address,o1_0.city,o1_0.first_name,o1_0.last_name,o1_0.telephone from owners o1_0 where o1_0.last_name like ? escape '\'
Hibernate: select p1_0.owner_id,p1_0.id,p1_0.birth_date,p1_0.name,t1_0.id,t1_0.name from pets p1_0 left join types t1_0 on t1_0.id=p1_0.type_id where p1_0.owner_id in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) order by p1_0.name
Hibernate: select v1_0.pet_id,v1_0.id,v1_0.visit_date,v1_0.description from visits v1_0 where v1_0.pet_id in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) order by v1_0.visit_date
Hibernate: select o1_0.id,o1_0.address,o1_0.city,o1_0.first_name,o1_0.last_name,o1_0.telephone from owners o1_0 where o1_0.last_name like ? escape '\'
Hibernate: select o1_0.id,o1_0.address,o1_0.city,o1_0.first_name,o1_0.last_name,o1_0.telephone,p1_0.owner_id,p1_0.id,p1_0.birth_date,p1_0.name,t1_0.id,t1_0.name from owners o1_0 left join pets p1_0 on o1_0.id=p1_0.owner_id left join types t1_0 on t1_0.id=p1_0.type_id where o1_0.id=? order by p1_0.name
Hibernate: select v1_0.pet_id,v1_0.id,v1_0.visit_date,v1_0.description from visits v1_0 where v1_0.pet_id in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) order by v1_0.visit_date
Hibernate: insert into visits (visit_date,description,id) values (?,?,default)
Hibernate: select o1_0.id,o1_0.address,o1_0.city,o1_0.first_name,o1_0.last_name,o1_0.telephone,p1_0.owner_id,p1_0.id,p1_0.birth_date,p1_0.name,t1_0.id,t1_0.name from owners o1_0 left join pets p1_0 on o1_0.id=p1_0.owner_id left join types t1_0 on t1_0.id=p1_0.type_id where o1_0.id=? order by p1_0.name
Hibernate: select v1_0.pet_id,v1_0.id,v1_0.visit_date,v1_0.description from visits v1_0 where v1_0.pet_id=? order by v1_0.visit_date
Hibernate: select o1_0.id,o1_0.address,o1_0.city,o1_0.first_name,o1_0.last_name,o1_0.telephone,p1_0.owner_id,p1_0.id,p1_0.birth_date,p1_0.name,t1_0.id,t1_0.name from owners o1_0 left join pets p1_0 on o1_0.id=p1_0.owner_id left join types t1_0 on t1_0.id=p1_0.type_id where o1_0.id=? order by p1_0.name
Hibernate: select v1_0.pet_id,v1_0.id,v1_0.visit_date,v1_0.description from visits v1_0 where v1_0.pet_id in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) order by v1_0.visit_date
Hibernate: select o1_0.id,o1_0.address,o1_0.city,o1_0.first_name,o1_0.last_name,o1_0.telephone,p1_0.owner_id,p1_0.id,p1_0.birth_date,p1_0.name,t1_0.id,t1_0.name from owners o1_0 left join pets p1_0 on o1_0.id=p1_0.owner_id left join types t1_0 on t1_0.id=p1_0.type_id where o1_0.id=? order by p1_0.name
Hibernate: select v1_0.pet_id,v1_0.id,v1_0.visit_date,v1_0.description from visits v1_0 where v1_0.pet_id in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) order by v1_0.visit_date
Hibernate: select pt1_0.id,pt1_0.name from types pt1_0 order by pt1_0.name
Hibernate: insert into pets (birth_date,name,type_id,id) values (?,?,?,default)
Hibernate: select o1_0.id,o1_0.address,o1_0.city,o1_0.first_name,o1_0.last_name,o1_0.telephone from owners o1_0 where o1_0.last_name like ? escape '\'
Hibernate: insert into owners (address,city,first_name,last_name,telephone,id) values (?,?,?,?,?,default)
Hibernate: select o1_0.id,o1_0.address,o1_0.city,o1_0.first_name,o1_0.last_name,o1_0.telephone from owners o1_0 where o1_0.last_name like ? escape '\'
Hibernate: select o1_0.id,o1_0.address,o1_0.city,o1_0.first_name,o1_0.last_name,o1_0.telephone,p1_0.owner_id,p1_0.id,p1_0.birth_date,p1_0.name,t1_0.id,t1_0.name from owners o1_0 left join pets p1_0 on o1_0.id=p1_0.owner_id left join types t1_0 on t1_0.id=p1_0.type_id where o1_0.id=? order by p1_0.name
Hibernate: select v1_0.pet_id,v1_0.id,v1_0.visit_date,v1_0.description from visits v1_0 where v1_0.pet_id=? order by v1_0.visit_date
Hibernate: select o1_0.id,o1_0.address,o1_0.city,o1_0.first_name,o1_0.last_name,o1_0.telephone,p1_0.owner_id,p1_0.id,p1_0.birth_date,p1_0.name,t1_0.id,t1_0.name from owners o1_0 left join pets p1_0 on o1_0.id=p1_0.owner_id left join types t1_0 on t1_0.id=p1_0.type_id where o1_0.id=? order by p1_0.name
Hibernate: select v1_0.pet_id,v1_0.id,v1_0.visit_date,v1_0.description from visits v1_0 where v1_0.pet_id in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) order by v1_0.visit_date
Hibernate: select pt1_0.id,pt1_0.name from types pt1_0 order by pt1_0.name
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.087 s -- in org.springframework.samples.petclinic.service.ClinicServiceTests
[INFO] Running org.springframework.samples.petclinic.owner.PetControllerTests
2026-04-21T15:35:22.474+02:00  INFO 93616 --- [           main] t.c.s.AnnotationConfigContextLoaderUtils : Could not detect default configuration classes for test class [org.springframework.samples.petclinic.owner.PetControllerTests]: PetControllerTests does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-04-21T15:35:22.477+02:00  INFO 93616 --- [           main] .b.t.c.SpringBootTestContextBootstrapper : Found @SpringBootConfiguration org.springframework.samples.petclinic.PetClinicApplication for test class org.springframework.samples.petclinic.owner.PetControllerTests
2026-04-21T15:35:22.479+02:00  INFO 93616 --- [           main] o.s.b.d.r.RestartApplicationListener     : Restart disabled due to context in which it is running


              |\      _,,,--,,_
             /,`.-'`'   ._  \-;;,_
  _______ __|,4-  ) )_   .;.(__`'-'__     ___ __    _ ___ _______
 |       | '---''(_/._)-'(_\_)   |   |   |   |  |  | |   |       |
 |    _  |    ___|_     _|       |   |   |   |   |_| |   |       | __ _ _
 |   |_| |   |___  |   | |       |   |   |   |       |   |       | \ \ \ \
 |    ___|    ___| |   | |      _|   |___|   |  _    |   |      _|  \ \ \ \
 |   |   |   |___  |   | |     |_|       |   | | |   |   |     |_    ) ) ) )
 |___|   |_______| |___| |_______|_______|___|_|  |__|___|_______|  / / / /
 ==================================================================/_/_/_/

:: Built with Spring Boot :: 4.0.3


2026-04-21T15:35:22.490+02:00  INFO 93616 --- [           main] o.s.s.p.owner.PetControllerTests         : Starting PetControllerTests using Java 26-ea with PID 93616 (started by i560383 in /Users/i560383_1/code/experiments/test-order/spring-petclinic)
2026-04-21T15:35:22.490+02:00  INFO 93616 --- [           main] o.s.s.p.owner.PetControllerTests         : No active profile set, falling back to 1 default profile: "default"
2026-04-21T15:35:22.576+02:00  INFO 93616 --- [           main] o.s.b.t.m.w.SpringBootMockServletContext : Initializing Spring TestDispatcherServlet ''
2026-04-21T15:35:22.576+02:00  INFO 93616 --- [           main] o.s.t.web.servlet.TestDispatcherServlet  : Initializing Servlet ''
2026-04-21T15:35:22.576+02:00  INFO 93616 --- [           main] o.s.t.web.servlet.TestDispatcherServlet  : Completed initialization in 0 ms
2026-04-21T15:35:22.578+02:00  INFO 93616 --- [           main] o.s.s.p.owner.PetControllerTests         : Started PetControllerTests in 0.099 seconds (process running for 9.072)
[INFO] Running org.springframework.samples.petclinic.owner.PetControllerTests$ProcessUpdateFormHasErrors
2026-04-21T15:35:22.619+02:00  INFO 93616 --- [           main] t.c.s.AnnotationConfigContextLoaderUtils : Could not detect default configuration classes for test class [org.springframework.samples.petclinic.owner.PetControllerTests$ProcessUpdateFormHasErrors]: ProcessUpdateFormHasErrors does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-04-21T15:35:22.621+02:00  INFO 93616 --- [           main] .b.t.c.SpringBootTestContextBootstrapper : Found @SpringBootConfiguration org.springframework.samples.petclinic.PetClinicApplication for test class org.springframework.samples.petclinic.owner.PetControllerTests$ProcessUpdateFormHasErrors
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.035 s -- in org.springframework.samples.petclinic.owner.PetControllerTests$ProcessUpdateFormHasErrors
[INFO] Running org.springframework.samples.petclinic.owner.PetControllerTests$ProcessCreationFormHasErrors
2026-04-21T15:35:22.654+02:00  INFO 93616 --- [           main] t.c.s.AnnotationConfigContextLoaderUtils : Could not detect default configuration classes for test class [org.springframework.samples.petclinic.owner.PetControllerTests$ProcessCreationFormHasErrors]: ProcessCreationFormHasErrors does not declare any static, non-private, non-final, nested classes annotated with @Configuration.
2026-04-21T15:35:22.656+02:00  INFO 93616 --- [           main] .b.t.c.SpringBootTestContextBootstrapper : Found @SpringBootConfiguration org.springframework.samples.petclinic.PetClinicApplication for test class org.springframework.samples.petclinic.owner.PetControllerTests$ProcessCreationFormHasErrors
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.060 s -- in org.springframework.samples.petclinic.owner.PetControllerTests$ProcessCreationFormHasErrors
[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.241 s -- in org.springframework.samples.petclinic.owner.PetControllerTests
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 50, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  20.576 s
[INFO] Finished at: 2026-04-21T15:35:22+02:00
[INFO] ------------------------------------------------------------------------
```
