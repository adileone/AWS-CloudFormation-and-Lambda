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
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;

import org.json.JSONArray;
import org.json.JSONObject;


/**
 * Lambda function to update projects Dashboard for CloudWatch alarms
 * 
 * @author Alessandro Di Leone
 *
 */

public class UpdateDashboards implements RequestHandler<ScheduledEvent, String> {

	private LambdaLogger logger;
	private AmazonCloudWatch cwClient;

	public UpdateDashboards() {

		cwClient = AmazonCloudWatchClientBuilder.defaultClient();
	}

	@SuppressWarnings("unused")
	@Override
	public String handleRequest(ScheduledEvent input, Context context) {

		logger = context.getLogger();
		logger.log("\nEntering " + this.getClass().getSimpleName() + " handler \n");

		String region = System.getenv("Region");
		if (region == null) {
			throw new RuntimeException("Undefined env variable Region, cannot proceed.");
		}

		List<MetricAlarm> alarms = listAlarms(cwClient);

		Map<String, List<String>> alarmMap = new HashMap<String, List<String>>();

		Set<String> projects = new HashSet<String>();
		for (MetricAlarm alarm : alarms) {

			String project = alarm.getAlarmName().split("-")[0].replaceAll("[ %]*", "");
			projects.add(project);
		}

		for (String project : projects) {
			List<String> alarmList = new ArrayList<String>();
			for (MetricAlarm alarm : alarms) {
				String projectId = alarm.getAlarmName().split("-")[0].replaceAll("[ %]*", "");
				if (projectId.equals(project)) {
					alarmList.add(alarm.getAlarmArn());
				}
			}
			alarmMap.put(project, alarmList);
		}

		for (String project : projects) {
			
			String dashboardBody= checkDashboard(project);
			
			if (dashboardBody != null){

				logger.log("\nupdating " + project+"-dashboard");

				JSONObject db = new JSONObject(dashboardBody);
				JSONArray widgets = db.getJSONArray("widgets");

				ArrayList<String> alreadyInDashboardsAlarms = new ArrayList<String>(); 

				int lastX=0;
				int lastY=0;
			
				for (int i=0 ; i<widgets.length(); i++){

					JSONObject widget = widgets.getJSONObject(i);
				
					lastX=widget.getInt("x");
					lastY=widget.getInt("y");
				
					if (widget.getString("type").equals("alarm")){

						JSONObject properties = widget.getJSONObject("properties");
					
						ArrayList<String> alarmsArnList = new ArrayList<String>();
			
						for (Object alarmArn :  properties.getJSONArray("alarms").toList()) {
							alarmsArnList.add((String) alarmArn);
						}

						if(alarmsArnList.size()>100){
							logger.log("\nToo many alarms for "+project+". First 100 considered.");
							continue;
						}

						for (String a : alarmMap.get(project)){
							if (!alarmsArnList.contains(a)){
								properties.append("alarms", a);
							}				
						}
					}

					if (widget.getString("type").equals("metric")){

						try {
							JSONObject properties = widget.getJSONObject("properties");
							JSONObject annotations = properties.getJSONObject("annotations");
							JSONArray widgetAlarms = annotations.getJSONArray("alarms");

							if (widgetAlarms.length()==1){
							alreadyInDashboardsAlarms.add((String) widgetAlarms.toList().get(0));}
							else {logger.log("custom widget");
							continue;}						
							
						} catch (Exception e) {
							logger.log("custom widget");
							continue;
						}	
					}
				}
				
				for (String alarm : alarmMap.get(project)){
					if (!alreadyInDashboardsAlarms.contains(alarm)){

						if (lastX==18){
							lastX=0;
							lastY=lastY+6;
						}
						else { lastX=lastX+6;
							}
					
						JSONObject newWidget = createMetricWidget(alarm,lastX,lastY);
						widgets.put(newWidget);
					}
				}

				PutDashboardResult putDashboardResult = cwClient.putDashboard(new PutDashboardRequest().withDashboardName(project+"-dashboard").withDashboardBody(db.toString()));	
			}	

			else {

				logger.log("\ncreating " + project+"-dashboard");

				JSONObject db = new JSONObject();

				JSONArray widgets = new JSONArray();

				JSONObject alarmWidget = new JSONObject();
				alarmWidget.put("type", "alarm");
				alarmWidget.put("x", 0);
				alarmWidget.put("y", 0);
				alarmWidget.put("width", 24);
				alarmWidget.put("height", 6);

				JSONObject properties = new JSONObject();
				properties.put("title","Alarms overview");

				List<String> perProjectAlarmsList = alarmMap.get(project);
				if (perProjectAlarmsList.size()>100){
					logger.log("\nToo many alarms for "+project+ ". First 100 considered.");
					perProjectAlarmsList=perProjectAlarmsList.subList(0, 100);
				}
				JSONArray alarmsArray = new JSONArray(perProjectAlarmsList);
				
				properties.put("alarms", alarmsArray);

				alarmWidget.put("properties", properties);

				widgets.put(alarmWidget);

				int lastX=0;
				int lastY=6;

				for (String newAlarm : perProjectAlarmsList){

					JSONObject newWidget = createMetricWidget(newAlarm,lastX,lastY);
					widgets.put(newWidget);

					if (lastX==18){
						lastX=0;
						lastY=lastY+6;
					}
					else {
						lastX=lastX+6;
					}
				}

				db.put("widgets",widgets);

				PutDashboardResult putDashboardResult = cwClient.putDashboard(new PutDashboardRequest().withDashboardName(project+"-dashboard").withDashboardBody(db.toString()));

			}
		}			
		return "ok";
	}
	
	public String checkDashboard(String project){

		String dashboardBody;
		try {
			dashboardBody = cwClient.getDashboard(new GetDashboardRequest().withDashboardName(project+"-dashboard")).getDashboardBody();
			
		} catch (DashboardNotFoundErrorException e) {
			dashboardBody=null;
		}

		return dashboardBody;
	}

	public JSONObject createMetricWidget(String alarm,int lastX, int lastY){
				
		JSONObject newWidget = new JSONObject();
		newWidget.put("type", "metric");
		newWidget.put("x", lastX);
		newWidget.put("y", lastY);
		newWidget.put("width", 6);
		newWidget.put("height", 6);
		
		JSONObject properties = new JSONObject();
		properties.put("title",alarm.split(":")[6]);
		
		JSONObject annotations = new JSONObject();
		JSONArray alarmsArray = new JSONArray();
		alarmsArray.put(alarm);
		
		annotations.put("alarms",alarmsArray);
		
		properties.put("annotations", annotations);
		
		newWidget.put("properties", properties);

		return newWidget;
	}


	public List<MetricAlarm> listAlarms(AmazonCloudWatch cwClient) {

		DescribeAlarmsRequest describeAlarmsRequest = new DescribeAlarmsRequest();
		DescribeAlarmsResult describeAlarmsResult = cwClient.describeAlarms(describeAlarmsRequest);

		List<MetricAlarm> alarms = new ArrayList<MetricAlarm>();
		alarms.addAll(describeAlarmsResult.getMetricAlarms());

		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}			

		while (describeAlarmsResult.getNextToken() != null) {

			DescribeAlarmsRequest describeAlarmsRequestNextToken = new DescribeAlarmsRequest()
					.withNextToken(describeAlarmsResult.getNextToken());
			DescribeAlarmsResult response = cwClient.describeAlarms(describeAlarmsRequestNextToken);
			alarms.addAll(response.getMetricAlarms());

			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			describeAlarmsResult = response;
		}

		return alarms;
	}
}