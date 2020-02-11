import os
import gzip
import json
import base64
import boto3
from botocore.exceptions import ClientError
from botocore.vendored import requests
from datetime import datetime

def handler(event, context):
    param_name = os.environ['RECIPIENTS_PARAM_NAME']
    sender = os.environ['SENDER'] 
    ssm = boto3.client('ssm')
    parameter = ssm.get_parameter(Name=param_name, WithDecryption=False)
    
    OP_URL = os.environ['OPERATIONS_HOOK_URL']
    RO_URL = os.environ['READONLY_HOOK_URL']
    

    if("Records" in event): 
        
        eventType = event['Records'][0]['EventSource']
        subject = event['Records'][0]['Sns']['Subject']
        timestamp = event['Records'][0]['Sns']['Timestamp']
        
        s=event['Records'][0]['Sns']['Message']
        
        message = json.loads(s)
        
        alarmName = message['AlarmName']
        alarmDescription = message['AlarmDescription']
        newStateValue = message['NewStateValue']
        newStateReason = message['NewStateReason']
        stateChangeTime = message['StateChangeTime']
        
        #trigger
        metricName = message['Trigger']['MetricName']
        comparisonOperator = message['Trigger']['ComparisonOperator']
        threshold = message['Trigger']['Threshold']
        
        
        BODY_TEXT = ("A {} event was received: {}.\n\nTimestamp: {}\n\nAlarmName: {}\nAlarmDescription: {}\nNewStateValue: {}\nNewStateReason: {}\nStateChangeTime: {}\n\nTrigger:\n\tMetricName: {}\n\tComparisonOperator: {}\n\tThreshold: {}".format(eventType, subject, timestamp, alarmName, alarmDescription, newStateValue, newStateReason, stateChangeTime, metricName, comparisonOperator, threshold)) 
    
        responseOP = requests.post(OP_URL,json={'text': BODY_TEXT})
        responseRO = requests.post(RO_URL,json={'text': BODY_TEXT})
    
    else: 
        cw_data = event['awslogs']['data']
        compressed_payload = base64.b64decode(cw_data)
        uncompressed_payload = gzip.decompress(compressed_payload)
        payload = json.loads(uncompressed_payload)
        
        
        logGroup=payload["logGroup"]
        logStream=payload["logStream"]
        id=payload["logEvents"][0]["id"]
        enc_timestamp=payload["logEvents"][0]["timestamp"]
        timestamp=datetime.utcfromtimestamp(enc_timestamp/1000).strftime('%Y-%m-%d %H:%M:%S')
        message=payload["logEvents"][0]["message"]
        BODY_TEXT = ("An ERROR log was added to the following logGroup: {}\n\nlogStream: {}\nid: {}\ntimestamp: {}\nmessage: {}.\n \nPlease follow this link to access the related logGroup: https://eu-central-1.console.aws.amazon.com/cloudwatch/home?region=eu-central-1#logStream:group={}").format(logGroup,logStream,id,timestamp,message,logGroup)
    
    
        # Replace sender@example.com with your "From" address.
        # This address must be verified with Amazon SES.
        SENDER = "WIPO PCT Monitoring <"+sender+">"
        
        # Replace recipient@example.com with a "To" address. If your account 
        # is still in the sandbox, this address must be verified.
        RECIPIENT = parameter['Parameter']['Value']
        RECIPIENT = RECIPIENT.split(",")

        # Specify a configuration set. If you do not want to use a configuration
        # set, comment the following variable, and the 
        # ConfigurationSetName=CONFIGURATION_SET argument below.
        # CONFIGURATION_SET = "ConfigSet"
        
        # If necessary, replace us-west-2 with the AWS Region you're using for Amazon SES.
        AWS_REGION = "eu-central-1"
        
        # The subject line for the email.
        SUBJECT ="Alert: {}".format(payload["subscriptionFilters"][0])
                    
        # The HTML body of the email.
        BODY_HTML = """<html>
         <head></head>
         <body>
           <h1>An ERROR log was added to the following logGroup: {}</h1>
           <br><strong>log:</strong> {}<br>
           <br>logStream: {}<br>
           id: {}<br>
           timestamp: {}<br>
           <br> Please follow this link to access the related <a href=https://eu-central-1.console.aws.amazon.com/cloudwatch/home?region=eu-central-1#logStream:group={}> logGroup</a>.
         </body>
         <footer>
         <p><small>This email was sent with
            <a href='https://aws.amazon.com/ses/'>Amazon SES</a> using the
            <a href='https://aws.amazon.com/sdk-for-python/'> AWS SDK for Python (Boto)</a>.</small></p>
         </footer>
         </html>   
         """.format(logGroup,message,logStream,id,timestamp,logGroup)            
        
        # The character encoding for the email.
        CHARSET = "UTF-8"
        
        # Create a new SES resource and specify a region.
        client = boto3.client('ses',region_name=AWS_REGION)
        
        # Try to send the email.
        try:
            #Provide the contents of the email.
            response = client.send_email(
                Destination={
                    'ToAddresses': 
                        RECIPIENT,
                },
                Message={
                    'Body': {
                        'Html': {
                            'Charset': CHARSET,
                            'Data': BODY_HTML,
                        },
                        'Text': {
                            'Charset': CHARSET,
                            'Data': BODY_TEXT,
                        },
                    },
                    'Subject': {
                        'Charset': CHARSET,
                        'Data': SUBJECT,
                    },
                },
                Source=SENDER,
                # If you are not using a configuration set, comment or delete the
                # following line
                #ConfigurationSetName=CONFIGURATION_SET,
            )
        # Display an error if something goes wrong.	
        except ClientError as e:
            print("ERROR --> ", e.response['Error']['Message'])
        else:
            print("Email sent! Message ID:"),
            print(response['MessageId'])
            
        logGroupLink = "https://eu-central-1.console.aws.amazon.com/cloudwatch/home?region=eu-central-1#logStream:group={}".format(logGroup)    
        
        responseOP = requests.post(OP_URL,json={'text': BODY_TEXT})
        responseRO = requests.post(RO_URL,json={'text': BODY_TEXT})
        
    return "SENT"