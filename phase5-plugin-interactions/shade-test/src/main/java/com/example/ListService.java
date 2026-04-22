package com.example;

import com.google.common.collect.Lists;
import java.util.List;

public class ListService {
    public <T> List<T> createList(T... items) {
        return Lists.newArrayList(items);
    }
    
    public <T> int getSize(List<T> list) {
        return list.size();
    }
}
