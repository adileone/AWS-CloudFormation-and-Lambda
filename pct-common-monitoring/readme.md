# Onboarding ManageAlarms-ManageLogEvents (MonitoringLambda) and RestartService

## ManageAlarms

### Service to observe: 

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
	
### MonitoringLambda:

	- Attach the new Event to trigger the ManageAlarms Lambda

		Events:
		  EPCTSNS:
	        Type: SNS
	        Properties:
	          Topic:
	            Fn::ImportValue: "epct-healthyhost-monitoring-topic"
	 
	         
## ManageLogEvents

### Service to observe: 

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
	
### MonitoringLambda:
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
	              

## RestartService

### Service to observe: 

	- ServiceName MUST stick to the naming convention "projectName-*" e.g. (epct-ws-app);

	- Include in the CloudFormation template the Alarm based on MemoryConsumption or CpuUtilization CloudWatch metrics (also in this case please stick to the naming convention Projectname-*);

	- Include in the CloudFormation template the SNSTopic to trigger the lambda and create the related CloudFormationExport;
	
	  EpctComponentHealthyHostSNSTopic:
	    Description: SNS topic for Memory Consumption monitoring and RestartService
	    Value: !Ref MemoryConsumptionSNSTopic
	    Export: 
	      Name: "epct-memconsumption-monitoring-topic"   
	
### RestartTask:
			
	- Attach the new Event to trigger the ManageLogEvents Lambda
      
      Events:
        EpctMemConsSNSTopic:
          Type: SNS
          Properties:
            Topic:
              Fn::ImportValue: "epct-memconsumption-monitoring-topic"               			
