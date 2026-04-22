package com.example

import spock.lang.Specification
import spock.lang.Unroll

class MathUtilsSpec extends Specification {
    
    private MathUtils mathUtils
    
    def setup() {
        mathUtils = new MathUtils()
    }
    
    def "should add two numbers correctly"() {
        when:
        int result = mathUtils.add(2, 3)
        
        then:
        result == 5
    }
    
    def "should multiply two numbers correctly"() {
        when:
        int result = mathUtils.multiply(4, 5)
        
        then:
        result == 20
    }
    
    @Unroll
    def "should identify prime number #number as prime: #isPrime"() {
        when:
        boolean result = mathUtils.isPrime(number)
        
        then:
        result == isPrime
        
        where:
        number | isPrime
        2      | true
        3      | true
        4      | false
        5      | true
        17     | true
        20     | false
        29     | true
    }
    
    def "should throw exception when dividing by zero"() {
        when:
        mathUtils.divide(10, 0)
        
        then:
        thrown(IllegalArgumentException)
    }
}
