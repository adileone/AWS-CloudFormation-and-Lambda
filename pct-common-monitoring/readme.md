## Onboarding Monitoring framework

The monitoring framework is composed by 6 lambda function, and is capable of monitor AWS resources by inspecting logs, performing health-check and automatically reacting with specific remedy actions:

- **ManageAlarms** lambda: Triggered by CloudWatch Alarms, it sends custom email parsing the alarm's information when it goes off.
- **ManageLogEvents** lambda: Triggered by CloudWatch Logs filter pattern, it sends email-notification about specific log patterns happening in the service's log groups and allow to directly download logs without accessing the AWS console.
- **RestartService** lambda: Triggered by CludWatch Alarms based on resources utilization (e.g. memory consumption) it is capable of restarting ECS tasks when the alarm's threshold is met. 
- **ScanEndpoint** lambda: Inventory function capable of scanning CloudMap registered services to insert the related endpoint into a DynamoDB table for future healthcheck calls.
- **Healthcheck** lambda: From the DynamoDb table filled by the ScanEndpoint Lambda, this function takes the endpoint to perform healthcheck from an internal standpoint.
- **ExportLogs** lambda: Scheduled function to archive CloudWatch Logs to S3 first and then to Glacier after 30 days.
	  	
### ManageAlarms

#### Service to observe: 

	- ServiceName MUST stick to the naming convention "projectName-*" e.g. (epct-ws-app);
	- Include in the CloudFormation template: Sender and ListOfRecipients ssm parameter;
	
	  MonitoringAlarmsSenderEpct:
	    Type: AWS::SSM::Parameter
	    Properties:
	      Name: "/epct/alarms/sender"
	      Type: String
	      Value: "alessandro.dileone@wipo.int"
	      Description: SSM Parameter for ManageAlarms lambda Email epct-sender
	
	  MonitoringAlarmsRecipientsEpct:
	    Type: AWS::SSM::Parameter
	    Properties:
	      Name: "/epct/alarms/recipients"
	      Type: StringList
	      Value: "alessandro.dileone@wipo.int,claudio.morgia@wipo.int"
	      Description: SSM Parameter for ManageAlarms lambda Email epct-recipients
	      
	  N.B. Please adhere to the naming convention for the parameters: "/projectName/alarms/sender" and "/projectName/alarms/recipients"
	  N.B. All the email addresses provided to these two parameter has to be registered in the account's SES service   

	- Include in the CloudFormation template the SNSTopic to trigger the lambda and create the related CloudFormationExport;
	
	  EpctComponentHealthyHostSNSTopic:
    	Description: SNS topic for HealthyHost monitoring
    	Value: !Ref HealthyHostSNSTopic
    	Export: 
      	  Name: "epct-healthyhost-monitoring-topic"
	
	- Create all the alarms you need and use the SNSTopic to send events to the lambda;
	
#### MonitoringLambda:

	- Attach the new Event to trigger the ManageAlarms Lambda

		Events:
		  EPCTSNS:
	        Type: SNS
	        Properties:
	          Topic:
	            Fn::ImportValue: "epct-healthyhost-monitoring-topic"
	 
	         
### ManageLogEvents

#### Service to observe: 

	- ServiceName MUST stick to the naming convention "projectName-*" e.g. (epct-ws-app);
	- Include in the CloudFormation template: Sender and ListOfRecipients ssm parameter;
	
	  MonitoringLogsSenderEpct:
	    Type: AWS::SSM::Parameter
	    Properties:
	      Name: "/epct/logs/sender"
	      Type: String
	      Value: "alessandro.dileone@wipo.int"
	      Description: SSM Parameter for ManageLogEvents Email epct-sender
	
	  MonitoringLogsRecipientsEpct:
	    Type: AWS::SSM::Parameter
	    Properties:
	      Name: "/epct/logs/recipients"
	      Type: StringList
	      Value: "alessandro.dileone@wipo.int,claudio.morgia@wipo.int"
	      Description: SSM Parameter for ManageLogEvents Email epct-recipients   
	      
	  N.B. Please adhere to the naming convention for the parameters: "/projectName/logs/sender" and "/projectName/logs/recipients"  

	- Include in the CloudFormation template the LogGroup you want to monitor (also in this case please stick to the naming convention Projectname-*);
	
      EpctWSLogGroup:
	    Type: AWS::Logs::LogGroup
	    Properties:
	      LogGroupName: epct-ws/fargate
	      RetentionInDays: !Ref LogRetentionDays
	
#### MonitoringLambda:
**N.B.** "?Exception ?ERROR" is a default filter. 
Please refer to this link to identify the best Log pattern to catch: [Filter and pattern syntax](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/FilterAndPatternSyntax.html#matching-terms-events)
	
	- Define with a new CloudFormation template parameter the Subscription filter you want to implement and the LogGroup you want to observe with that filter;
	  
		  EPCTFilterPattern:
		    Type: String
		    Description: The EPCT filter pattern
		    Default: "?Exception ?ERROR"
		    
		  EPCTLogGroupName:
		    Type: String
		    Default: epct-ws/fargate   
			
	- Attach the new Event to trigger the ManageLogEvents Lambda
      
	      Events:
	        EPCTLog:
	          Type: CloudWatchLogs
	          Properties:
	            FilterPattern:
	              Ref: EPCTFilterPattern
	            LogGroupName:
	              Ref: EPCTLogGroupName 
	              

### RestartService

#### Service to observe: 

	- ServiceName MUST stick to the naming convention "projectName-*" e.g. (epct-ws-app);

	- Include in the CloudFormation template the Alarm based on MemoryConsumption or CpuUtilization CloudWatch metrics (also in this case please stick to the naming convention Projectname-*);

	- Include in the CloudFormation template the SNSTopic to trigger the lambda and create the related CloudFormationExport;
	
	  EpctComponentHealthyHostSNSTopic:
	    Description: SNS topic for Memory Consumption monitoring and RestartService
	    Value: !Ref MemoryConsumptionSNSTopic
	    Export: 
	      Name: "epct-memconsumption-monitoring-topic"   
	
#### RestartTask:
			
	- Attach the new Event to trigger the ManageLogEvents Lambda
      
      Events:
        EpctMemConsSNSTopic:
          Type: SNS
          Properties:
            Topic:
              Fn::ImportValue: "epct-memconsumption-monitoring-topic"
              
              
### ScanEndpoint and HealthCheck

In order to enable healthcheck on a cloudmap endpoint you have to tag the related servicediscovery service with the "uri" and "port" tags:

e.g.

		  EpctComponentDiscoveryService:
		    Type: AWS::ServiceDiscovery::Service
		    Properties:
		      Description: Discovery Service for the ePCT Application
		      DnsConfig:
		        RoutingPolicy: MULTIVALUE
		        DnsRecords:
		          - TTL: 60
		            Type: A
		          - TTL: 60
		            Type: SRV
		      HealthCheckCustomConfig:
		        FailureThreshold: 1
		      Name: !Ref ServiceName
		      NamespaceId: !Ref CZPrivateNamespace
		      Tags: 
				  - Key: "uri"
				    Value: "value1"   --> this will be the predefined uri to get the service response, for example "/servlet/health" 
				  - Key: "port"
				    Value: "value2"   --> the port to access the service, for example "8080"
				    
These functions are scheduled to run once every 4hour for the inventory and every 2 minutes for the healthcheck.
Under the hood, the ScanEndpoint lambda will associate the pct-common-monitoring vpc with the hosted zone we put the service in. 
After that it will include the endpoint in a DynamoDb table in order to make the healthcheck lambda take the endpoint from there and do the HTTP requests. 


### ExportLogs              
                             			
In order to Export service logs from CloudWatch to S3 you have to tag the log group you want to export with the following [Key: "ExportToS3", Value="true"].
Unfortunately for the moment this can be done through the AWS Command Line (CLI) only: 

e.g.  aws cloudwatch tag-resource --resource-arn "arn:aws:cloudwatch:region:account-id:resource-id" --tags Key=ExportToS2,Value=true

The logs will be then archived to S3 (standard) first and after 30 days they will be moved to GLACIER.