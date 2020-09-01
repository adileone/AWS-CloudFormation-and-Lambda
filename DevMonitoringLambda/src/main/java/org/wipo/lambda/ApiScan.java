package org.wipo.lambda;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.apigateway.AmazonApiGateway;
import com.amazonaws.services.apigateway.AmazonApiGatewayClientBuilder;
import com.amazonaws.services.apigateway.model.GetRestApisRequest;
import com.amazonaws.services.apigateway.model.GetRestApisResult;
import com.amazonaws.services.apigateway.model.RestApi;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.servicediscovery.AWSServiceDiscovery;
import com.amazonaws.services.servicediscovery.AWSServiceDiscoveryClientBuilder;
import com.amazonaws.services.servicediscovery.model.GetNamespaceRequest;
import com.amazonaws.services.servicediscovery.model.GetNamespaceResult;
import com.amazonaws.services.servicediscovery.model.GetServiceRequest;
import com.amazonaws.services.servicediscovery.model.GetServiceResult;
import com.amazonaws.services.servicediscovery.model.ListServicesRequest;
import com.amazonaws.services.servicediscovery.model.ListServicesResult;
import com.amazonaws.services.servicediscovery.model.ServiceSummary;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException; 

/**
 * Lambda function to scan services/API endpoint and insert URLs in DynamoDB table
 * 
 * @author Alessandro Di Leone
 *
 */

public class ApiScan implements RequestHandler<Object, String> {

	private LambdaLogger logger;
	private AmazonApiGateway apiGatewayClient;
	private DynamoDbClient dynamoDbClient;
	private AWSServiceDiscovery serviceDiscoveryclient;

	public ApiScan() {

		apiGatewayClient = AmazonApiGatewayClientBuilder.defaultClient();
		dynamoDbClient = DynamoDbClient.builder().build();
		serviceDiscoveryclient = AWSServiceDiscoveryClientBuilder.standard().build();

	}


	@Override
	public String handleRequest(Object input, Context context) {

		logger = context.getLogger();
		logger.log("\nEntering " + this.getClass().getSimpleName() + " handler \n");

		String tableName = System.getenv("TableName");
		if (tableName==null) {
			throw new RuntimeException("Undefined env variable TableName, cannot proceed.");
		}

		List<String> endpointListApi = getApisEndpoint();
		if (endpointListApi.isEmpty()) {return "No RestApi to check";};

		for (String endpoint : endpointListApi) {

			HashMap<String,AttributeValue> itemValues = new HashMap<String,AttributeValue>();
			itemValues.put("endpoint", AttributeValue.builder().s(endpoint).build());
			itemValues.put("type", AttributeValue.builder().s("api").build());

			PutItemRequest request = PutItemRequest.builder()
					.tableName(tableName)
					.item(itemValues)
					.build();

			try {
				dynamoDbClient.putItem(request);
				logger.log("\n" + tableName +" was successfully updated "+ endpoint +" inserted");
			} catch (ResourceNotFoundException e) {
				throw new RuntimeException("Error: The table "+tableName+" can't be found.\nBe sure that it exists and that you've typed its name correctly!");
			} catch (DynamoDbException e) {
				throw new RuntimeException(e.getMessage());
			}
		}		

		List<String> endpointListService = getServicesEndpoint();
		if (endpointListService.isEmpty()) {return "No Service to check";};

		for (String endpoint : endpointListService) {

			HashMap<String,AttributeValue> itemValues = new HashMap<String,AttributeValue>();
			itemValues.put("endpoint", AttributeValue.builder().s(endpoint).build());
			itemValues.put("type", AttributeValue.builder().s("service").build());

			PutItemRequest request = PutItemRequest.builder()
					.tableName(tableName)
					.item(itemValues)
					.build();

			try {
				dynamoDbClient.putItem(request);
				logger.log("\n" + tableName +" was successfully updated "+ endpoint +" inserted");
			} catch (ResourceNotFoundException e) {
				throw new RuntimeException("Error: The table "+tableName+" can't be found.\nBe sure that it exists and that you've typed its name correctly!");
			} catch (DynamoDbException e) {
				throw new RuntimeException(e.getMessage());
			}
		}


		return "ok";
	}


	private ArrayList<String> getServicesEndpoint() {

		ListServicesRequest listServicesRequest = new ListServicesRequest();
		ListServicesResult listServiceResponse = serviceDiscoveryclient.listServices(listServicesRequest);

		List<ServiceSummary> serviceList = listServiceResponse.getServices();

		ArrayList<String> Urls = new ArrayList<String>();

		for (ServiceSummary service : serviceList ) {

			String id = service.getId();

			GetServiceRequest getServicesRequest = new GetServiceRequest().withId(id);
			GetServiceResult getServiceResponse = serviceDiscoveryclient.getService(getServicesRequest);

			String namespaceID = getServiceResponse.getService().getNamespaceId();
			String name = getServiceResponse.getService().getName();

			GetNamespaceRequest namespaceRequest = new GetNamespaceRequest().withId(namespaceID);
			GetNamespaceResult namespaceResponse = serviceDiscoveryclient.getNamespace(namespaceRequest);

			String namespace = namespaceResponse.getNamespace().getName();

			String endpoint = name+"."+namespace;

			Urls.add(endpoint);

		}

		return Urls;
	}


	public ArrayList<String> getApisEndpoint() {

		GetRestApisRequest getRestApiRequest = new GetRestApisRequest();
		GetRestApisResult getRestApiResult = apiGatewayClient.getRestApis(getRestApiRequest);

		logger.log("\nNumber of Apis found : "+getRestApiResult.getItems().size());

		Map<String,String> apisToCheck = new HashMap<String,String>();


		for (RestApi api : getRestApiResult.getItems()) {
			logger.log("\nName: "+api.getName()+" Id: "+api.getId());

			try {
				Map<String, String> apiTags = api.getTags();
				if (apiTags.containsKey("healthcheck")) {
					apisToCheck.put(api.getId(), apiTags.get("healthcheck"));
				}

			} catch (Exception e) {
				logger.log("\nno tags found for "+api.getName());			}
		}

		logger.log("\nNumber of Apis to check : "+ apisToCheck.size());

		ArrayList<String> endpointList = new ArrayList<String>();
		apisToCheck.forEach((k, v) -> endpointList.add("https://"+k+".execute-api.eu-central-1.amazonaws.com/Prod"+v));

		return endpointList;
	}
}