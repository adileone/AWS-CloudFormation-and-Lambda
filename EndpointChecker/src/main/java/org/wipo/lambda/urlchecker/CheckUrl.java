package org.wipo.lambda.urlchecker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

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
 * Lambda function to check CloudMap services endpoint
 * 
 * @author Claudio Morgia, Alessandro Di Leone
 *
 */

public class CheckUrl implements RequestHandler<Object, String> {

	private DynamoDbClient DDBclient;
	private AWSServiceDiscovery SDclient;
	private LambdaLogger logger;

	public CheckUrl() {
		DDBclient = DynamoDbClient.builder().build();
		SDclient = AWSServiceDiscoveryClientBuilder.standard().build();
	}

	@Override
	public String handleRequest(Object input, Context context) {

		logger = context.getLogger();

		logger.log("\nEntering " + this.getClass().getSimpleName() + " handler \n");

		String tableName = System.getenv("TableName");
		if (tableName==null) {
			throw new RuntimeException("Undefined env variable TableName, cannot proceed.");
		}

		ListServicesRequest listServicesRequest = new ListServicesRequest();
		ListServicesResult listServiceResponse = SDclient.listServices(listServicesRequest);

		List<ServiceSummary> serviceList = listServiceResponse.getServices();

		ArrayList<String> Urls = new ArrayList<String>();

		for (ServiceSummary service : serviceList ) {

			String id = service.getId();

			GetServiceRequest getServicesRequest = new GetServiceRequest().withId(id);
			GetServiceResult getServiceResponse = SDclient.getService(getServicesRequest);

			String namespaceID = getServiceResponse.getService().getNamespaceId();
			String name = getServiceResponse.getService().getName();

			GetNamespaceRequest namespaceRequest = new GetNamespaceRequest().withId(namespaceID);
			GetNamespaceResult namespaceResponse = SDclient.getNamespace(namespaceRequest);

			String namespace = namespaceResponse.getNamespace().getName();

			String endpoint = name+"."+namespace;

			Urls.add(endpoint);

		}

		HashMap<String,AttributeValue> itemValues = new HashMap<String,AttributeValue>();

		for (String a : Urls) {

			UUID uuid = UUID.randomUUID();

			itemValues.put("id", AttributeValue.builder().s(uuid.toString()).build());
			itemValues.put("endpoint", AttributeValue.builder().s(a).build());

			// Create a PutItemRequest object
			PutItemRequest request = PutItemRequest.builder()
					.tableName(tableName)
					.item(itemValues)
					.build();

			try {
				DDBclient.putItem(request);
				logger.log("\n" + tableName +" was successfully updated "+a+" inserted");
			} catch (ResourceNotFoundException e) {
				throw new RuntimeException("Error: The table "+tableName+" can't be found.\nBe sure that it exists and that you've typed its name correctly!");
			} catch (DynamoDbException e) {
				throw new RuntimeException(e.getMessage());
			}
		}

		return "Ok";
	}
}

