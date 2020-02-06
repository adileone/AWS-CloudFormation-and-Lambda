import boto3

def handler(event,context):
    client = boto3.client('ecs')
    response = client.update_service(
    cluster='CloudFormationClusterSampleWebApp',
    service='CloudFormationServiceSampleWebApp',
    desiredCount=3,
    taskDefinition='CloudFormationTaskDefinitionFamilySampleWebApp:5',
    deploymentConfiguration={
        'maximumPercent': 200,
        'minimumHealthyPercent': 100
    },
    networkConfiguration={
        'awsvpcConfiguration': {
            'subnets': [
                'subnet-00a332c19939bf894',
                'subnet-01f977c4d47c2493e',
                'subnet-058937e8a0332667a'
                ],
            'assignPublicIp': 'ENABLED',
            'securityGroups': [
                'sg-0f896d88e75066584'
            ]
        }
    },
    platformVersion='LATEST',
    forceNewDeployment=False,
    healthCheckGracePeriodSeconds=0)
    return str(response)