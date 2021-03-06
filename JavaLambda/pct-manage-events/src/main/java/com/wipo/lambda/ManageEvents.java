package com.wipo.lambda;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.zip.GZIPInputStream;

import org.json.JSONArray;
import org.json.JSONObject;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClientBuilder;
import com.amazonaws.services.simpleemail.model.Body;
import com.amazonaws.services.simpleemail.model.Content;
import com.amazonaws.services.simpleemail.model.Destination;
import com.amazonaws.services.simpleemail.model.Message;
import com.amazonaws.services.simpleemail.model.SendEmailRequest;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterResult;
import com.sun.org.apache.xml.internal.security.exceptions.Base64DecodingException;
import com.sun.org.apache.xml.internal.security.utils.Base64;

public class ManageEvents implements RequestHandler<Object, String> {

	@Override
	public String handleRequest(Object input, Context context) {

		LinkedHashMap<String,Object> requestsLHM =  (LinkedHashMap<String, Object>) input;

		//Is SNSEvent
		try {
			ArrayList<LinkedHashMap<String,Object> > records = (ArrayList<LinkedHashMap<String,Object> > ) requestsLHM.get("Records");

			//event0
			LinkedHashMap<String,Object> rec0 = (LinkedHashMap<String,Object>) records.get(0);
			String eventType = (String) rec0.get("EventSource");

			//sns
			LinkedHashMap<String,Object> sns = (LinkedHashMap<String,Object>) rec0.get("Sns");
			String message = (String) sns.get("Message");
			String subject = (String) sns.get("Subject");
			String timestamp = (String) sns.get("Timestamp");

			//message
			JSONObject msg = new JSONObject(message);
			String alarmName = msg.getString("AlarmName");
			String alarmDescription = msg.getString("AlarmDescription");
			String newStateValue = msg.getString("NewStateValue");
			String newStateReason = msg.getString("NewStateReason");
			String stateChangeTime = msg.getString("StateChangeTime"); 
			String AWSAccountID=msg.getString("AWSAccountId");

			//trigger
			JSONObject trigger = msg.getJSONObject("Trigger");
			String metricName = trigger.getString("MetricName");
			String comparisonOperator = trigger.getString("ComparisonOperator");

			String[] thresholdArray = newStateReason.split(" ");
			String threshold = thresholdArray[12]; 

			//dimensions
			JSONArray dimensions = trigger.getJSONArray("Dimensions");
			JSONObject dimTG = dimensions.getJSONObject(0);
			String TargetGroup=dimTG.getString("value");

			String sender = getSender(alarmName);
			ArrayList<String> recipients = getRecipients(alarmName);

			String TGurl="https://eu-central-1.console.aws.amazon.com/ec2/v2/home?region=eu-central-1#TargetGroups:search=arn:aws:elasticloadbalancing:eu-central-1:"+AWSAccountID+":"+TargetGroup;

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
					+ "<br> <a href=\""+TGurl+"\" style=\"margin:5px;background-color:#EB7035;float:left;border:1px solid #EB7035;border-radius:3px;color:#ffffff;display:inline-block;font-family:sans-serif;font-size:12px;line-height:44px;text-align:center;text-decoration:none;width:100px;-webkit-text-size-adjust:none;mso-hide:all;\">Go to TargetGroup</a>"
					+ "<br></br>"
					+ "</div>"
					+ "</body>"
					+ "<footer>"
					+ "<p><br></br><br></br><small>This email was sent with"
					+ "<a href='https://aws.amazon.com/ses/'> Amazon SES</a> using the"
					+ "<a href='https://aws.amazon.com/sdk-for-java/'> AWS SDK for Java</a>.</small></p>"
					+ "</footer>"
					+ "</html>"; 


			sendEmail(sender, recipients, subject, html_body, text_body);

			return "DONE";

		} 

		//Is CWLog
		catch (Exception e) {

			LinkedHashMap<String,Object> myRequests = (LinkedHashMap<String,Object>) requestsLHM.get("awslogs");
			String data = (String) myRequests.get("data");

			try {

				JSONObject event = new JSONObject(unzip(data));

				//logDescription 
				JSONArray logEvents = event.getJSONArray("logEvents");
				JSONObject logJson = logEvents.getJSONObject(0);
				String id=logJson.getString("id");
				String timestamp=new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(logJson.getLong("timestamp"));
				String message= logJson.getString("message");

				String logGroup = event.getString("logGroup");
				String logStream=event.getString("logStream");
				String logGroupLink = "https://eu-central-1.console.aws.amazon.com/cloudwatch/home?region=eu-central-1#logStream:group="+logGroup;
				String logStreamLink = "https://eu-central-1.console.aws.amazon.com/cloudwatch/home?region=eu-central-1#logEventViewer:group="+logGroup+";stream="+logStream;

				JSONArray subFilters = event.getJSONArray("subscriptionFilters");
				String sf = subFilters.getString(0);
				String subject ="Alert: "+sf;

				String sender = getSender(logGroup);
				ArrayList<String> recipients = getRecipients(logGroup);

				String text_body = "An ERROR log was added to the following logGroup: "+logGroup+"\n\n"
						+ "logStream: "+logStream+"\n"
						+ "id: "+id+"\n"
						+ "timestamp: "+timestamp+"\n"
						+ "message: "+message+".\n"
						+ "\nPlease follow this link to access the related logGroup:"+logGroupLink
						+ "\nPlease follow this link to access the related logStream:"+logStreamLink;

				String html_body = "<html>"
						+ "<head></head>"
						+ "<body>"
						+ "<h1>An ERROR log was added to the following logGroup: "+logGroup+"</h1>"
						+ "<p>Please open this message in you browser in order to navigate to the log stream. <p>"
						+ "<br><strong>log:</strong> "+message+"<br>"
						+ "<br>logStream: "+logStream+"<br>"
						+ "id: "+id+"<br>"
						+ "timestamp: "+timestamp+"<br>"
						+ "<div>"
						+ "<br> <a href=\""+logGroupLink+"\" style=\"margin:5px;background-color:#EB7035;border:1px solid #EB7035;border-radius:3px;color:#ffffff;display:inline-block;font-family:sans-serif;font-size:12px;line-height:44px;text-align:center;text-decoration:none;width:100px;-webkit-text-size-adjust:none;mso-hide:all;\">Go to LogGroup</>"
						+ "<br> <a href=\""+logStreamLink+"\" style=\"margin:5px;background-color:#EB7035;float:left;border:1px solid #EB7035;border-radius:3px;color:#ffffff;display:inline-block;font-family:sans-serif;font-size:12px;line-height:44px;text-align:center;text-decoration:none;width:100px;-webkit-text-size-adjust:none;mso-hide:all;\">Go to LogStream</a>"
						+ "<br></br>"
						+ "</div>"
						+ "</body>"
						+ "<footer>"
						+ "<p><small>This email was sent with"
						+ "<a href='https://aws.amazon.com/ses/'> Amazon SES</a> using the"
						+ "<a href='https://aws.amazon.com/sdk-for-java/'> AWS SDK for Java</a>.</small></p>"
						+ "</footer>"
						+ "</html>";

				sendEmail(sender, recipients, subject, html_body, text_body);

				return "DONE";

			} catch (Base64DecodingException e1) {
				e1.printStackTrace();
				return(e1.toString());
			} catch (Exception e1) {
				e1.printStackTrace();
				return(e1.toString());
			}
		}       	
	}


	public ArrayList<String> getRecipients(String projID) {
		String[] splitted = projID.split("-", 2); 
		String project=splitted[0];			
		String paramListOfRecipientsName="/"+project+"/ListOfRecipients";			
		GetParameterResult listOfRecipientsResult = getParameter(paramListOfRecipientsName);
		String recipientsList = listOfRecipientsResult.getParameter().getValue();
		String[] mails = recipientsList.split(",");
		ArrayList<String> recipients = new ArrayList<String>();
		for (String s : mails) {recipients.add(s);}
		return recipients;
	}


	public String getSender(String projID) {
		String[] splitted = projID.split("-", 2); 
		String project=splitted[0];
		String paramSenderName="/"+project+"/alarms/sender";
		GetParameterResult senderResult = getParameter(paramSenderName);
		String sender = senderResult.getParameter().getValue();
		return sender;
	}


	public String unzip(String zip) throws Base64DecodingException, Exception {
		String encoded = zip;
		byte[] compressed = Base64.decode(encoded);

		if ((compressed == null) || (compressed.length == 0)) {
			throw new IllegalArgumentException("Cannot unzip null or empty bytes");
		}
		if (!isZipped(compressed)) {
			System.out.println(compressed);
		}

		ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(compressed);
		GZIPInputStream gzipInputStream = new GZIPInputStream(byteArrayInputStream);
		InputStreamReader inputStreamReader = new InputStreamReader(gzipInputStream, StandardCharsets.UTF_8);
		BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
		StringBuilder output = new StringBuilder();
		String line;
		while((line = bufferedReader.readLine()) != null){
			output.append(line);}
		return output.toString();

	}


	public static boolean isZipped(final byte[] compressed) {
		return (compressed[0] == (byte) (GZIPInputStream.GZIP_MAGIC)) && (compressed[1] == (byte) (GZIPInputStream.GZIP_MAGIC >> 8));
	}


	public GetParameterResult getParameter(String parameterName) {
		AWSSimpleSystemsManagement ssm = AWSSimpleSystemsManagementClientBuilder.defaultClient();		
		GetParameterRequest request = new GetParameterRequest();
		request.setName(parameterName);
		request.setWithDecryption(false);
		return ssm.getParameter(request); 
	}


	public void sendEmail(String from, ArrayList<String> to, String subject, String html_body, String text_body) throws IOException {

		try {
			AmazonSimpleEmailService client = 
					AmazonSimpleEmailServiceClientBuilder.standard()
					.withRegion(Regions.EU_CENTRAL_1).build();
			SendEmailRequest request = new SendEmailRequest()
					.withDestination(
							new Destination().withToAddresses(to))
					.withMessage(new Message()
							.withBody(new Body()
									.withHtml(new Content()
											.withCharset("UTF-8").withData(html_body))
									.withText(new Content()
											.withCharset("UTF-8").withData(text_body)))
							.withSubject(new Content()
									.withCharset("UTF-8").withData(subject)))
					.withSource(from);
			client.sendEmail(request);
			System.out.println("Email sent!");
		} catch (Exception ex) {
			System.out.println("The email was not sent. Error message: " 
					+ ex.getMessage());
		}
	}

}