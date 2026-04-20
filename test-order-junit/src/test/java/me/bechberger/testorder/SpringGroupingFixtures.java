package me.bechberger.testorder;

import org.springframework.boot.test.context.SpringBootTest;

class SpringConfigA {}

class SpringConfigB {}

@SpringBootTest(classes = SpringConfigA.class)
class SpringGroupedFastTest {}

@SpringBootTest(classes = SpringConfigA.class)
class SpringGroupedSlowTest {}

@SpringBootTest(classes = SpringConfigB.class)
class SpringOtherContextTest {}
