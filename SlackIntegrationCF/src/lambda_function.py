import os
import gzip
import json
import base64
import boto3
from botocore.exceptions import ClientError
from botocore.vendored import requests


def handler(event, context):
    param_name = os.environ['RECIPIENTS_PARAM_NAME']
    ssm = boto3.client('ssm')
    parameter = ssm.get_parameter(Name=param_name, WithDecryption=False)
    
    print(event) 
 
    if("Records" in event): 
        slack_message = {
            'EventSource': event['Records'][0]['EventSource'],
            'EventSubscriptionArn': event['Records'][0]['EventSubscriptionArn'],
            'Sns:': event['Records'][0]['Sns']
        }
        
        OP_URL = os.environ['OPERATIONS_HOOK_URL']
        RO_URL = os.environ['READONLY_HOOK_URL']

        parsed=slack_message
        responseOP = requests.post(OP_URL,json={'text': json.dumps(parsed, indent=4, sort_keys=True,ensure_ascii=False)})
        responseRO = requests.post(RO_URL,json={'text': json.dumps(parsed, indent=4, sort_keys=True,ensure_ascii=False)})
    
    else: 
        cw_data = event['awslogs']['data']
        compressed_payload = base64.b64decode(cw_data)
        uncompressed_payload = gzip.decompress(compressed_payload)
        payload = json.loads(uncompressed_payload)
    
    
        # Replace sender@example.com with your "From" address.
        # This address must be verified with Amazon SES.
        SENDER = "Sender Name <alessandroawstest@gmail.com>"
        
        # Replace recipient@example.com with a "To" address. If your account 
        # is still in the sandbox, this address must be verified.
        RECIPIENT = parameter['Parameter']['Value']
        RECIPIENT = RECIPIENT.split(",")
        # RECIPIENT = ['alessandroawstest@gmail.com', 'dileone.ale@gmail.com']
        
        # Specify a configuration set. If you do not want to use a configuration
        # set, comment the following variable, and the 
        # ConfigurationSetName=CONFIGURATION_SET argument below.
        # CONFIGURATION_SET = "ConfigSet"
        
        # If necessary, replace us-west-2 with the AWS Region you're using for Amazon SES.
        AWS_REGION = "eu-central-1"
        
        # The subject line for the email.
        SUBJECT = "Amazon SES Test (SDK for Python)"
        
        # The email body for recipients with non-HTML email clients.
        BODY_TEXT = ("Amazon SES Test (Python)\r\n"
                      "This email was sent with Amazon SES using the "
                      "AWS SDK for Python (Boto)."
                     )
                    
        # The HTML body of the email.
        BODY_HTML = """<html>
         <head></head>
         <body>
           <h1>Amazon SES Test (SDK for Python)</h1>
           <p>This email was sent with
             <a href='https://aws.amazon.com/ses/'>Amazon SES</a> using the
             <a href='https://aws.amazon.com/sdk-for-python/'>
               AWS SDK for Python (Boto)</a>.</p>
         </body>
         </html>
                     """            
        
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
            
            
        slack_message = {
            'channel': payload['logGroup'],
            'text': payload['logStream'],
            'username': payload['logEvents'][0]['message']
        }

            
        OP_URL = os.environ['OPERATIONS_HOOK_URL']
        RO_URL = os.environ['READONLY_HOOK_URL']
        

        parsed=slack_message
        responseOP = requests.post(OP_URL,json={'text': json.dumps(parsed, indent=4, sort_keys=True,ensure_ascii=False)})
        responseRO = requests.post(RO_URL,json={'text': json.dumps(parsed, indent=4, sort_keys=True,ensure_ascii=False)})

    return "SENT"