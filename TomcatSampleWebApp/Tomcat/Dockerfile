FROM tomcat:8.0-alpine

LABEL maintainer="ale"
 
ADD tomcat-users.xml $CATALINA_HOME/conf

ADD SampleWebApp.war /usr/local/tomcat/webapps/

EXPOSE 8080

CMD ["catalina.sh", "run"]
