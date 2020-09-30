package com.solidap.admin;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.functions.Context;
import com.google.cloud.functions.RawBackgroundFunction;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Email;
import com.sendgrid.helpers.mail.objects.Personalization;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Customer;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class OnStripeCharge implements RawBackgroundFunction {

    private static final Logger logger = Logger.getLogger(OnStripeCharge.class.getName());
    private static final Firestore FIRESTORE = FirestoreOptions.getDefaultInstance().getService();

    private static final Gson gson = new Gson();

    @Override
    public void accept(String json, Context context) {

        Properties prop = new Properties();

        try (InputStream input = new FileInputStream("src/main/resources/application.properties")) {
            prop.load(input);
        } catch (IOException ex) {
            logger.info("ERROR in file(): " + ex.getMessage());

        }

        Stripe.apiKey = prop.getProperty("stripeApiKey");

        String affectedDoc = context.resource().split("/documents/")[1].replace("\"", "");
        try {
            DocumentReference order = FIRESTORE.document(affectedDoc).getParent().getParent();

            JsonElement token = JsonParser.
                    parseString(gson.toJson(FIRESTORE.document(affectedDoc).get().get().get("payment"))).getAsJsonObject().get("token");

            Map <String ,Object > orderDetails = order.get().get().getData();
            Map <String ,Object > personalInfo = (Map<String, Object>) orderDetails.get("personalInfo");
            Map <String ,Object > address = (Map<String, Object>) orderDetails.get("address");
            Map <String ,Object > cart = (Map<String, Object>) orderDetails.get("cart");

            Map<String, Object> customerParams = new HashMap<>();
            customerParams.put(
                    "email",
                    personalInfo.get("email")
            );
            try {
                Customer customer = Customer.create(customerParams);

                Map<String, Object> params = new HashMap<>();
                params.put("customer", customer.getId());
                params.put("amount",(int) (Float.valueOf((String)cart.get("grandTotal")) * 100));
                params.put("currency", prop.getProperty("currency").toLowerCase());
                params.put("source", token.getAsJsonObject().get("id").getAsString());

                Charge charge = Charge.create(params);

                FIRESTORE.document(affectedDoc).getParent().getParent().update("status" , "paid");

                SendGrid sg = new SendGrid(prop.getProperty("sendGridKey"));
                Email from = new Email(prop.getProperty("sendGridFrom"));
                Email to = new Email(personalInfo.get("email").toString());
                Mail mail = new Mail();
                mail.setFrom(from);
                Personalization dynamicData = new Personalization();
                dynamicData.addTo(to);
                dynamicData.addDynamicTemplateData("cartId" , order.getId());
                dynamicData.addDynamicTemplateData("personalInfo" , personalInfo);
                dynamicData.addDynamicTemplateData("address" , address);
                dynamicData.addDynamicTemplateData("cart" , cart);
                mail.addPersonalization(dynamicData);
                mail.setTemplateId(prop.getProperty("sendGridConfirmationTemplateId"));
                Request emailRequest = new Request();
                try {
                    emailRequest.setMethod(Method.POST);
                    emailRequest.setEndpoint("mail/send");
                    emailRequest.setBody(mail.build());
                    Response emailResponse = sg.api(emailRequest);
                    logger.info("emailResponse.getStatusCode() --------> " + emailResponse.getStatusCode());
                    logger.info("emailResponse.getBody() --------> " + emailResponse.getBody());
                    logger.info("emailResponse.getHeaders() --------> " + emailResponse.getHeaders());
                } catch (IOException ex) {
                    logger.info("EMAIL ERROR --------> " + ex.getMessage());
                }

            } catch (StripeException e) {
                FIRESTORE.document(affectedDoc).getParent().getParent().update("status" , "failed");
                e.printStackTrace();
            }
        } catch (InterruptedException e) {
            logger.log(Level.SEVERE,e.getMessage());
        } catch (ExecutionException e) {
            logger.log(Level.SEVERE,e.getMessage());
        }
    }
}
