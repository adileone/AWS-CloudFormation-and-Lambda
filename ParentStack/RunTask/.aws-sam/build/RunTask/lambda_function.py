import os
import boto3

def handler(event,context):
    
    sub_a = os.environ['SERVICE_SUBNET_A']
    sub_b = os.environ['SERVICE_SUBNET_B']
    sub_c = os.environ['SERVICE_SUBNET_C']
    
    sg = os.environ['SERVICE_SEC_GROUP']
    
    td = os.environ['TASK_DEF']
    
    client = boto3.client('ecs')
    response = client.update_service(
    cluster='CloudFormationClusterSampleWebApp',
    service='CloudFormationServiceSampleWebApp',
    desiredCount=3,
    taskDefinition=td,
    deploymentConfiguration={
        'maximumPercent': 200,
        'minimumHealthyPercent': 100
    },
    networkConfiguration={
        'awsvpcConfiguration': {
            'subnets': [
                sub_a,
                sub_b,
                sub_c
                ],
            'assignPublicIp': 'ENABLED',
            'securityGroups': [sg]
        }
    },
    platformVersion='LATEST',
    forceNewDeployment=False,
    healthCheckGracePeriodSeconds=0)
    return "Service Updated"