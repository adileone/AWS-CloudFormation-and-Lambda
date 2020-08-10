#!/bin/bash
mvn clean
mvn -Dmaven.test.skip=true install
mvn clean
zip -r /tmp/package.zip * 
mv /tmp/package.zip .