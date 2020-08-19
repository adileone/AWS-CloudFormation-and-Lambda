package org.wipo.lambda;

import java.io.IOException;
import java.util.ArrayList;

import org.joda.time.DateTime;
import org.json.JSONObject;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.amazonaws.services.lambda.runtime.events.SNSEvent.SNS;
import com.amazonaws.services.lambda.runtime.events.SNSEvent.SNSRecord;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClientBuilder;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder;
/**
 * Lambda function to manage SNS notifications
 * 
 * @author Alessandro Di Leone
 *
 */

public class ManageAlarms implements RequestHandler<SNSEvent, String> {

	private AmazonSimpleEmailService sesClient;
	private LambdaLogger logger;
	private AWSSimpleSystemsManagement ssmClient;
	private AWSSecurityTokenService stsClient;

	public ManageAlarms() {

		ssmClient = AWSSimpleSystemsManagementClientBuilder.defaultClient();

		stsClient = AWSSecurityTokenServiceClientBuilder.standard()
				.withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
				.withRegion("eu-central-1")
				.build();

		String crossAccountRoleArn = System.getenv("CrossAccountRoleARN");

		if (crossAccountRoleArn==null) {
			sesClient = AmazonSimpleEmailServiceClientBuilder.standard().withRegion(Regions.EU_CENTRAL_1).build();
		}else {
			sesClient = AmazonSimpleEmailServiceClientBuilder.standard()
					.withCredentials(new AWSStaticCredentialsProvider(CommonUtils.getSTSsession(crossAccountRoleArn, stsClient)))
					.withRegion(Regions.EU_CENTRAL_1).build();}
	}


	@Override
	public String handleRequest(SNSEvent input, Context context) {

		logger = context.getLogger();
		logger.log("\nEntering " + this.getClass().getSimpleName() + " handler \n");

		SNSRecord record = input.getRecords().get(0);
		String eventType = record.getEventSource();

		SNS sns = record.getSNS();
		logger.log("SNSEvent received: "+sns.getMessageId());

		//sns
		String message = sns.getMessage();
		String subject = sns.getSubject();
		DateTime timestamp = sns.getTimestamp(); 

		//message
		JSONObject msg = new JSONObject(message);
		String alarmName = msg.getString("AlarmName");
		String alarmDescription = msg.getString("AlarmDescription");
		String newStateValue = msg.getString("NewStateValue");
		String newStateReason = msg.getString("NewStateReason");
		String stateChangeTime = msg.getString("StateChangeTime"); 
		//		String AWSAccountID=msg.getString("AWSAccountId");

		//trigger
		JSONObject trigger = msg.getJSONObject("Trigger");
		String metricName = trigger.getString("MetricName");
		String comparisonOperator = trigger.getString("ComparisonOperator");

		String[] thresholdArray = newStateReason.split(" ");
		String threshold = thresholdArray[12]; 

		String sender = CommonUtils.getSender(alarmName, ssmClient);
		ArrayList<String> recipients = CommonUtils.getRecipients(alarmName, ssmClient);

		String alarmUrl="https://eu-central-1.console.aws.amazon.com/cloudwatch/home?region=eu-central-1#alarmsV2:alarm/"+alarmName;

		String text_body = "A "+eventType+" event was received: "+subject+".\n\n"
				+ "Timestamp: "+timestamp+"\n\n"
				+ "AlarmName: "+alarmName+"\n"
				+ "AlarmDescription: "+alarmDescription+"\nNewStateValue: "+newStateValue+"\nNewStateReason: "+newStateReason+"\nStateChangeTime: "+stateChangeTime+"\n\n"
				+ "Trigger:\n\tMetricName: "+metricName+"\n\tComparisonOperator: "+comparisonOperator+"\n\tThreshold: "+threshold+" \n\n" 
				+ "*Please select an action:*";


		String html_body = "<html>"
				+ "<head></head>"
				+ "<body>"
				+ "<h1>A "+eventType+" event was received: "+subject+".</h1>"
				+ "<p>Please open this message in your browser in order to navigate to the target group. <p>"
				+ "<br>Timestamp: "+timestamp+"<br>"
				+ "<br><strong>AlarmName:</strong> "+alarmName+"<br>"
				+ "<br>AlarmDescription: "+alarmDescription+"<br>NewStateValue: "+newStateValue+"<br>NewStateReason: "+newStateReason+"<br>StateChangeTime: "+stateChangeTime+"<br></br>"
				+ "<p>Trigger:</p>"
				+ "<ul>"
				+ "<li>MetricName: "+metricName+"</li>"
				+ "<li>ComparisonOperator: "+comparisonOperator+"</li>"
				+ "<li>Threshold: "+threshold+"</li>"
				+ "</ul>"
				+ "<div>"
				+ "<br> <a href=\""+alarmUrl+"\" style=\"margin:5px;background-color:#EB7035;float:left;border:1px solid #EB7035;border-radius:3px;color:#ffffff;display:inline-block;font-family:sans-serif;font-size:12px;line-height:44px;text-align:center;text-decoration:none;width:100px;-webkit-text-size-adjust:none;mso-hide:all;\">Alarm dashboard</a>"
				+ "<br></br>"
				+ "</div>"
				+ "</body>"
				+ "<footer>"
				+ "<p><br></br><br></br><small>This email was sent with"
				+ "<a href='https://aws.amazon.com/ses/'> Amazon SES</a> using the"
				+ "<a href='https://aws.amazon.com/sdk-for-java/'> AWS SDK for Java</a>.</small></p>"
				+ "</footer>"
				+ "</html>"; 


		try {
			return CommonUtils.sendEmail(sender, recipients, subject, html_body, text_body, sesClient);
		} catch (IOException e) {
			return e.getMessage();
		}

	}       	
}