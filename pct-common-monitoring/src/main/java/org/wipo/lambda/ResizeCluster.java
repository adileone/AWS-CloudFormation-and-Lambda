package org.wipo.lambda;

import java.util.LinkedHashMap;
import java.util.List;

import com.amazonaws.services.ecs.AmazonECS;
import com.amazonaws.services.ecs.AmazonECSClientBuilder;
import com.amazonaws.services.ecs.model.ListServicesRequest;
import com.amazonaws.services.ecs.model.ListServicesResult;
import com.amazonaws.services.ecs.model.UpdateServiceRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;

/**
 * Lambda function to resize an ECS cluster to a desired capacity
 * 
 * @author Claudio Morgia
 *
 */

public class ResizeCluster implements RequestHandler<Object, String> {

	@SuppressWarnings("unchecked")
	@Override
	public String handleRequest(Object event, Context context) {

		LinkedHashMap<String, Object> input = (LinkedHashMap<String, Object>) event;
		LambdaLogger logger = context.getLogger();

		logger.log("Entering " + this.getClass().getSimpleName() + " handler");

		String clusterName = (String) input.get("ClusterName");
		Integer desiredCount = Integer.valueOf((String) input.get("DesiredCount"));

		logger.log("\nStarting resize of cluster " + clusterName + " with desired count set to " + desiredCount);

		AmazonECS client = AmazonECSClientBuilder.standard().build();

		String nextToken=null;

		ListServicesRequest listServicesRequest = new ListServicesRequest().withCluster(clusterName);
		ListServicesResult listServicesResult;

		do {
			if (nextToken!=null) {
				listServicesRequest.withNextToken(nextToken);
			}
			listServicesResult=client.listServices(listServicesRequest);
			List<String> servicesARNs = listServicesResult.getServiceArns();

			logger.log("Processing resize of " + servicesARNs.size() + " services");
			for (String service : servicesARNs) {
				
				String serviceName = service.substring(service.lastIndexOf("/")+1);

				logger.log("Updating desired count for service " + serviceName + " to " + desiredCount);

				UpdateServiceRequest updateServiceRequest = new UpdateServiceRequest()
						.withCluster(clusterName)
						.withService(service)
						.withDesiredCount(desiredCount);

				client.updateService(updateServiceRequest);

				logger.log("Updated service " + serviceName);
			}
		} while( (nextToken=listServicesResult.getNextToken()) != null);

		return "SUCCESS";
	}
}
