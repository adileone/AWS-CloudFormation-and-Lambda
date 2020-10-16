package org.wipo.lambda;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.apigateway.AmazonApiGateway;
import com.amazonaws.services.apigateway.AmazonApiGatewayClientBuilder;
import com.amazonaws.services.apigateway.model.GetRestApisRequest;
import com.amazonaws.services.apigateway.model.GetRestApisResult;
import com.amazonaws.services.apigateway.model.GetStagesRequest;
import com.amazonaws.services.apigateway.model.GetStagesResult;
import com.amazonaws.services.apigateway.model.RestApi;
import com.amazonaws.services.apigateway.model.Stage;
import com.amazonaws.services.ecs.AmazonECS;
import com.amazonaws.services.ecs.AmazonECSClientBuilder;
import com.amazonaws.services.ecs.model.DescribeServicesRequest;
import com.amazonaws.services.ecs.model.DescribeServicesResult;
import com.amazonaws.services.ecs.model.Service;
import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.AWSLambdaClientBuilder;
import com.amazonaws.services.lambda.model.GetFunctionRequest;
import com.amazonaws.services.lambda.model.GetFunctionResult;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.amazonaws.services.route53.AmazonRoute53;
import com.amazonaws.services.route53.AmazonRoute53ClientBuilder;
import com.amazonaws.services.route53.model.AssociateVPCWithHostedZoneRequest;
import com.amazonaws.services.route53.model.AssociateVPCWithHostedZoneResult;
import com.amazonaws.services.route53.model.GetHostedZoneRequest;
import com.amazonaws.services.route53.model.ListHostedZonesByNameRequest;
import com.amazonaws.services.route53.model.ListHostedZonesByNameResult;
import com.amazonaws.services.route53.model.VPC;
import com.amazonaws.services.servicediscovery.AWSServiceDiscovery;
import com.amazonaws.services.servicediscovery.AWSServiceDiscoveryClientBuilder;
import com.amazonaws.services.servicediscovery.model.GetNamespaceRequest;
import com.amazonaws.services.servicediscovery.model.GetNamespaceResult;
import com.amazonaws.services.servicediscovery.model.GetServiceRequest;
import com.amazonaws.services.servicediscovery.model.GetServiceResult;
import com.amazonaws.services.servicediscovery.model.InstanceSummary;
import com.amazonaws.services.servicediscovery.model.ListInstancesRequest;
import com.amazonaws.services.servicediscovery.model.ListInstancesResult;
import com.amazonaws.services.servicediscovery.model.ListServicesRequest;
import com.amazonaws.services.servicediscovery.model.ListServicesResult;
import com.amazonaws.services.servicediscovery.model.ListTagsForResourceRequest;
import com.amazonaws.services.servicediscovery.model.ListTagsForResourceResult;
import com.amazonaws.services.servicediscovery.model.ServiceSummary;
import com.amazonaws.services.servicediscovery.model.Tag;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/**
 * Lambda function to scan services/API endpoint and insert URLs in DynamoDB
 * table
 * 
 * @author Alessandro Di Leone
 *
 */

public class EndpointScan implements RequestHandler<ScheduledEvent, String> {

	private LambdaLogger logger;
	private AmazonApiGateway apiGatewayClient;
	private DynamoDbClient dynamoDbClient;
	private AWSServiceDiscovery serviceDiscoveryclient;
	private AmazonECS ecsClient;
	private AmazonRoute53 r53Client;
	private AWSLambda lambdaClient;

	public EndpointScan() {

		apiGatewayClient = AmazonApiGatewayClientBuilder.defaultClient();
		dynamoDbClient = DynamoDbClient.builder().build();
		serviceDiscoveryclient = AWSServiceDiscoveryClientBuilder.standard().build();
		ecsClient = AmazonECSClientBuilder.standard().build();
		r53Client = AmazonRoute53ClientBuilder.standard().build();
		lambdaClient = AWSLambdaClientBuilder.defaultClient();
	}

	@Override
	public String handleRequest(ScheduledEvent input, Context context) {

		logger = context.getLogger();
		logger.log("\nEntering " + this.getClass().getSimpleName() + " handler \n");

		String tableName = System.getenv("TableName");
		if (tableName == null) {
			throw new RuntimeException("Undefined env variable TableName, cannot proceed.");
		}
		String region = System.getenv("Region");
		if (region == null) {
			throw new RuntimeException("Undefined env variable Region, cannot proceed.");
		}

		// APIs

		GetRestApisRequest getRestApiRequest = new GetRestApisRequest();
		GetRestApisResult getRestApiResult = apiGatewayClient.getRestApis(getRestApiRequest);

		List<RestApi> apiList = getRestApiResult.getItems();

		if (apiList.isEmpty()) {
			return "No RestApi to check";
		}
		;
		logger.log("\nNumber of Apis found : " + apiList.size());

		for (RestApi api : getRestApiResult.getItems()) {

			logger.log("\nName: " + api.getName() + " Id: " + api.getId());

			Map<String, String> apiTags = null;

			if (api.getTags() != null) {
				apiTags = api.getTags();
			} else {
				logger.log("\nno tags found for " + api.getName());
				continue;
			}

			if (apiTags.containsKey("healthcheck")) {

				String healthcheck = apiTags.get("healthcheck");

				GetStagesRequest getStagesRequest = new GetStagesRequest().withRestApiId(api.getId());
				GetStagesResult getStagesresult = apiGatewayClient.getStages(getStagesRequest);

				for (Stage stage : getStagesresult.getItem()) {

					String stageName = stage.getStageName();
					String endpoint = "https://" + api.getId() + ".execute-api." + region + ".amazonaws.com/"
							+ stageName + healthcheck;

					HashMap<String, AttributeValue> itemValues = new HashMap<String, AttributeValue>();
					itemValues.put("endpoint", AttributeValue.builder().s(endpoint).build());
					itemValues.put("type", AttributeValue.builder().s("api").build());

					PutItemRequest request = PutItemRequest.builder().tableName(tableName).item(itemValues).build();

					try {
						dynamoDbClient.putItem(request);
						logger.log("\n" + tableName + " was successfully updated " + endpoint + " inserted");
					} catch (ResourceNotFoundException e) {
						throw new RuntimeException("Error: The table " + tableName
								+ " can't be found.\nBe sure that it exists and that you've typed its name correctly!");
					} catch (DynamoDbException e) {
						throw new RuntimeException(e.getMessage());
					}
				}
			}

			else {
				logger.log("\nno tag healthcheck found for " + api.getName());
				continue;
			}
		}

		// Services

		GetFunctionRequest req = new GetFunctionRequest().withFunctionName(context.getFunctionName());
		GetFunctionResult res = lambdaClient.getFunction(req);
		String pctCommonVpcId = res.getConfiguration().getVpcConfig().getVpcId();

		HashMap<String, String> clusters = new HashMap<String, String>();

		ListServicesRequest listServicesRequest = new ListServicesRequest();
		ListServicesResult listServiceResponse = serviceDiscoveryclient.listServices(listServicesRequest);

		List<ServiceSummary> serviceList = listServiceResponse.getServices();

		logger.log("\nNumber of Services: " + serviceList.size());

		List<String> securityGroupSet = new ArrayList<String>();

		for (ServiceSummary service : serviceList) {

			ListTagsForResourceRequest tagRequest = new ListTagsForResourceRequest().withResourceARN(service.getArn());
			ListTagsForResourceResult tagResult = serviceDiscoveryclient.listTagsForResource(tagRequest);

			List<Tag> serviceTags = null;

			if (tagResult.getTags() != null) {
				serviceTags = tagResult.getTags();
			} else {
				logger.log("\nno tags found for service: " + service.getName());
				continue;
			}

			HashMap<String, String> tags = new HashMap<String, String>();

			for (Tag tag : serviceTags) {
				tags.put(tag.getKey(), tag.getValue());
			}

			if (tags.containsKey("uri") && tags.containsKey("port")) {

				String id = service.getId();

				GetServiceRequest getServicesRequest = new GetServiceRequest().withId(id);
				GetServiceResult getServiceResponse = serviceDiscoveryclient.getService(getServicesRequest);

				String namespaceID = getServiceResponse.getService().getNamespaceId();
				String name = getServiceResponse.getService().getName();

				ListInstancesRequest listInstancesRequest = new ListInstancesRequest().withServiceId(id);
				ListInstancesResult listInstancesResult = serviceDiscoveryclient.listInstances(listInstancesRequest);

				InstanceSummary instanceSummary = listInstancesResult.getInstances().get(0);
				String clusterName = instanceSummary.getAttributes().get("ECS_CLUSTER_NAME");
				String serviceName = instanceSummary.getAttributes().get("ECS_SERVICE_NAME");

				DescribeServicesRequest describeServiceRequest = new DescribeServicesRequest().withCluster(clusterName)
						.withServices(serviceName);
				DescribeServicesResult describeServicesResult = ecsClient.describeServices(describeServiceRequest);

				Service ecsService = describeServicesResult.getServices().get(0);

				String clusterArn = ecsService.getClusterArn();

				String projectName = null;

				if (clusters.containsKey(clusterArn)) {
					projectName = clusters.get(clusterArn);
					logger.log("cacheHit for " + clusterArn + "->" + projectName);
				} else {

					com.amazonaws.services.ecs.model.ListTagsForResourceResult listTagsResult = ecsClient
							.listTagsForResource(new com.amazonaws.services.ecs.model.ListTagsForResourceRequest()
									.withResourceArn(clusterArn));
					List<com.amazonaws.services.ecs.model.Tag> clusterTags = listTagsResult.getTags();

					for (com.amazonaws.services.ecs.model.Tag tagEcs : clusterTags) {
						if (tagEcs.getKey().equalsIgnoreCase("service")) {
							clusters.put(clusterArn, tagEcs.getValue());
							logger.log("inserting in cachemap : " + clusterArn + "->" + tagEcs.getValue());
						}
					}
					projectName = clusters.getOrDefault(clusterArn, "void");
				}

				List<String> secGroups = ecsService.getNetworkConfiguration().getAwsvpcConfiguration()
						.getSecurityGroups();

				GetNamespaceRequest namespaceRequest = new GetNamespaceRequest().withId(namespaceID);
				GetNamespaceResult namespaceResponse = serviceDiscoveryclient.getNamespace(namespaceRequest);

				String namespace = namespaceResponse.getNamespace().getName();

				ListHostedZonesByNameRequest listHostedZoneRequest = new ListHostedZonesByNameRequest()
						.withDNSName(namespace);
				ListHostedZonesByNameResult listHostedZoneResult = r53Client
						.listHostedZonesByName(listHostedZoneRequest);
				String hostedZoneId = listHostedZoneResult.getHostedZones().get(0).getId().split("/")[2];
				GetHostedZoneRequest hostedZoneRequest = new GetHostedZoneRequest().withId(hostedZoneId);
				List<VPC> vpcs = r53Client.getHostedZone(hostedZoneRequest).getVPCs();
				ArrayList<String> vpcListIds = new ArrayList<String>();
				for (VPC vpc : vpcs) {
					vpcListIds.add(vpc.getVPCId());
				}

				if (!vpcListIds.contains(pctCommonVpcId)) {

					logger.log("pct-common-vpc association with " + namespace + "hosted zone");
					VPC pctCommmonVpc = new VPC().withVPCRegion(region).withVPCId(pctCommonVpcId);

					AssociateVPCWithHostedZoneRequest request = new AssociateVPCWithHostedZoneRequest()
							.withHostedZoneId(hostedZoneId).withVPC(pctCommmonVpc)
							.withComment("association from pct-common-scanEndpoint");
					AssociateVPCWithHostedZoneResult response = r53Client.associateVPCWithHostedZone(request);

					logger.log(response.toString());
				}

				String endpoint = name + "." + namespace;
				String uri = tags.get("uri");
				String port = tags.get("port");

				// default "http"
				String scheme = tags.getOrDefault("scheme", "http");

				String url = scheme + "://" + endpoint + ":" + port + uri;

				HashMap<String, AttributeValue> itemValues = new HashMap<String, AttributeValue>();
				itemValues.put("endpoint", AttributeValue.builder().s(url).build());
				itemValues.put("type", AttributeValue.builder().s("service").build());

				StringBuilder strbul = new StringBuilder();
				for (String secGroup : secGroups) {
					securityGroupSet.add(secGroup);
					strbul.append(secGroup);
					strbul.append(",");
					strbul.setLength(strbul.length() - 1);
				}

				itemValues.put("security group", AttributeValue.builder().s(strbul.toString()).build());
				itemValues.put("project", AttributeValue.builder().s(projectName).build());
				itemValues.put("service", AttributeValue.builder().s(serviceName).build());

				PutItemRequest request = PutItemRequest.builder().tableName(tableName).item(itemValues).build();

				try {
					dynamoDbClient.putItem(request);
					logger.log("\n" + tableName + " was successfully updated " + endpoint + " inserted");
				} catch (ResourceNotFoundException e) {
					throw new RuntimeException("Error: The table " + tableName
							+ " can't be found.\nBe sure that it exists and that you've typed its name correctly!");
				} catch (DynamoDbException e) {
					throw new RuntimeException(e.getMessage());
				}
			}

			else {
				logger.log("\nno tag for healtcheck (uri - port) found for service: " + service.getName());
			}
		}

		return "ok";
	}
}
