package org.wipo.lambda;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.cloudwatch.model.DescribeAlarmsRequest;
import com.amazonaws.services.cloudwatch.model.DescribeAlarmsResult;
import com.amazonaws.services.cloudwatch.model.GetDashboardRequest;
import com.amazonaws.services.cloudwatch.model.MetricAlarm;
import com.amazonaws.services.cloudwatch.model.PutDashboardRequest;
import com.amazonaws.services.cloudwatch.model.PutDashboardResult;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.cloudwatch.model.DashboardNotFoundErrorException;
import com.amazonaws.services.cloudwatch.model.DeleteDashboardsRequest;
import com.amazonaws.services.cloudwatch.model.DeleteDashboardsResult;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;

import org.json.JSONArray;
import org.json.JSONObject;


/**
 * Lambda function to update projects Dashboard for CloudWatch alarms
 * 
 * @author Alessandro Di Leone
 *
 */

public class Cleanup implements RequestHandler<ScheduledEvent, String> {

	private LambdaLogger logger;
	private AmazonCloudWatch cwClient;

	public Cleanup() {

		cwClient = AmazonCloudWatchClientBuilder.defaultClient();
	}

	@Override
	public String handleRequest(ScheduledEvent input, Context context) {

		logger = context.getLogger();
		logger.log("\nEntering " + this.getClass().getSimpleName() + " handler \n");

		String del = System.getenv("ToDelete");
		if (del == null) {
			throw new RuntimeException("Undefined env variable ToDelete, cannot proceed.");
		}

		String[] toDel = del.split(",");
		DeleteDashboardsResult deleteDashboardsResult = cwClient.deleteDashboards(new DeleteDashboardsRequest().withDashboardNames(toDel));
		
		return "ok";
	}
}
	
	