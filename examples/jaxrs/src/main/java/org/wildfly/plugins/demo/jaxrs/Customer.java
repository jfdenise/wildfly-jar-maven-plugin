/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.wildfly.plugins.demo.jaxrs;

/**
 *
 * @author jdenise
 */
public class Customer {

    String name;
    String surname;
    Long id;
    public void setSurname(String surname) {
        this.surname = surname;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Object getName() {
       return name;
    }

    public Object getSurname() {
        return surname;
    }

    public Object getId() {
       return id;
    }
    
}
