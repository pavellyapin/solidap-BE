package com.solidap.admin;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.firestore.QueryDocumentSnapshot;
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
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.param.PaymentIntentCreateParams;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class OnTestStripeCharge implements RawBackgroundFunction {

    private static final Logger logger = Logger.getLogger(OnTestStripeCharge.class.getName());
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

        Stripe.apiKey = prop.getProperty("stripeTestApiKey");

        String affectedDoc = context.resource().split("/documents/")[1].replace("\"", "");
        try {
            DocumentReference order = FIRESTORE.document(affectedDoc).getParent().getParent();
            Map<String, Object> orderDetails = order.get().get().getData();
            Map<String, Object> personalInfo = (Map<String, Object>) orderDetails.get("personalInfo");
            Map<String, Object> address = (Map<String, Object>) orderDetails.get("address");
            Map<String, Object> cart = (Map<String, Object>) orderDetails.get("cart");
            JsonElement token = JsonParser.
                    parseString(gson.toJson(FIRESTORE.document(affectedDoc).get().get().get("payment"))).getAsJsonObject().get("token");

            if (token.getAsJsonObject().get("paymentIntent") != null) {
                PaymentIntent paymentIntent = PaymentIntent.retrieve(token.getAsJsonObject().get("paymentIntent").getAsString());
                paymentIntent.confirm();
                setPaidStatus(affectedDoc,cart);
                sendEmail(prop, order, personalInfo, cart, address);
            } else {
                Map<String, Object> customerParams = new HashMap<>();
                customerParams.put(
                        "email",
                        personalInfo.get("email")
                );
                customerParams.put("source", token.getAsJsonObject().get("id").getAsString());
                Customer customer = Customer.create(customerParams);

                PaymentMethod paymentMethod = PaymentMethod.retrieve(token.getAsJsonObject().get("id").getAsString());
                paymentMethod.setCustomer(customer.getId());

                PaymentIntentCreateParams createParams = PaymentIntentCreateParams.builder()
                        .setAmount((long) (Float.valueOf((String) cart.get("grandTotal")) * 100))
                        .setCurrency(prop.getProperty("currency").toLowerCase())
                        .setConfirm(true)
                        .setCustomer(customer.getId())
                        .setPaymentMethod(paymentMethod.getId())
                        .setConfirmationMethod(PaymentIntentCreateParams.ConfirmationMethod.MANUAL)
                        .build();

                PaymentIntent paymentIntent =
                        PaymentIntent.create(createParams);
                if (paymentIntent.getStatus().equals("requires_action")
                        && paymentIntent.getNextAction().getType().equals("use_stripe_sdk")) {
                    FIRESTORE.document(affectedDoc).getParent().getParent().update("status", paymentIntent.getClientSecret());
                } else if (paymentIntent.getStatus().equals("succeeded")) {
                    setPaidStatus(affectedDoc,cart);
                    sendEmail(prop, order, personalInfo, cart, address);
                } else {
                    FIRESTORE.document(affectedDoc).getParent().getParent().update("status", "failed");
                }
            }

        } catch (InterruptedException e) {
            logger.log(Level.SEVERE, e.getMessage());
        } catch (ExecutionException e) {
            logger.log(Level.SEVERE, e.getMessage());
        } catch (StripeException e) {
            FIRESTORE.document(affectedDoc).getParent().getParent().update("status", "failed");
            e.printStackTrace();
        }
    }

    public void setPaidStatus(String affectedDoc,Map<String, Object> cart) {
        try{
                long count = 0;
                double ordersTotal = 0;
                for (QueryDocumentSnapshot doc:FIRESTORE.document(affectedDoc).
                        getParent().getParent().getParent().getParent().getParent().get().get()
                        .getDocuments()) {
                    if (doc.getId().equals("testOrders")) {
                        if (doc.get("orderCount") !=null) {
                            count = (long) doc.get("orderCount");
                        }
                        if (doc.get("ordersTotal") !=null) {
                            ordersTotal = (double) doc.get("ordersTotal");
                        }
                    }
                }
                Map<String,Object> stats = new HashMap<String,Object>();
                stats.put("orderCount",count + 1);
                stats.put("ordersTotal",ordersTotal + Double.valueOf((String)cart.get("grandTotal")));
                FIRESTORE.document(affectedDoc).getParent().getParent().getParent().getParent().set(stats);
                FIRESTORE.document(affectedDoc).getParent().getParent().update("status", "paid");
            } catch (InterruptedException e) {
                logger.log(Level.SEVERE, e.getMessage());
            } catch (ExecutionException e) {
                logger.log(Level.SEVERE, e.getMessage());
            }

    }

    public void sendEmail(Properties prop,
                          DocumentReference order,
                          Map<String, Object> personalInfo,
                          Map<String, Object> cart,
                          Map<String, Object> address) {
        SendGrid sg = new SendGrid(prop.getProperty("sendGridKey"));
        Email from = new Email(prop.getProperty("sendGridFrom"));
        Email to = new Email(personalInfo.get("email").toString());
        Email admin = new Email(prop.getProperty("email"));
        Mail mail = new Mail();
        mail.setFrom(from);
        Personalization dynamicData = new Personalization();
        dynamicData.addTo(to);
        //dynamicData.addTo(admin);
        dynamicData.addDynamicTemplateData("cartId", order.getId());
        dynamicData.addDynamicTemplateData("personalInfo", personalInfo);
        dynamicData.addDynamicTemplateData("address", address);
        dynamicData.addDynamicTemplateData("cart", cart);
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
    }
}
