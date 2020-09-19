package com.solidap.admin;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.functions.Context;
import com.google.cloud.functions.RawBackgroundFunction;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.net.RequestOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class OnStripeCharge implements RawBackgroundFunction {

    private static final Logger logger = Logger.getLogger(OnStripeCharge.class.getName());
    private static final Firestore FIRESTORE = FirestoreOptions.getDefaultInstance().getService();

    private static final Gson gson = new Gson();

    @Override
    public void accept(String json, Context context) {

        Stripe.apiKey = "sk_test_qCjlmILpATvCaw1OqQkVyOw700RMFPOWLq";
        RequestOptions requestOptions = RequestOptions.builder()
                .setApiKey("sk_test_qCjlmILpATvCaw1OqQkVyOw700RMFPOWLq")
                .build();

        String affectedDoc = context.resource().split("/documents/")[1].replace("\"", "");
        try {
            JsonElement token = JsonParser.
                    parseString(gson.toJson(FIRESTORE.document(affectedDoc).get().get().get("payment"))).getAsJsonObject().get("token");

            JsonElement personalInfo = JsonParser.
                    parseString(gson.toJson(FIRESTORE.document(affectedDoc).getParent().getParent().get().get().get("personalInfo")));

            JsonElement cart = JsonParser.
                    parseString(gson.toJson(FIRESTORE.document(affectedDoc).getParent().getParent().get().get().get("cart")));

            Map<String, Object> customerParams = new HashMap<>();
            customerParams.put(
                    "email",
                    personalInfo.getAsJsonObject().get("email").getAsString()
            );
            try {
                Customer customer = Customer.create(customerParams);

                Map<String, Object> params = new HashMap<>();
                params.put("customer", customer.getId());
                params.put("amount",(int) (Float.valueOf(cart.getAsJsonObject().get("grandTotal").getAsString()) * 100));
                params.put("currency", "cad");
                params.put("source", token.getAsJsonObject().get("id").getAsString());

                Charge charge = Charge.create(params);

                FIRESTORE.document(affectedDoc).getParent().getParent().update("status" , "paid");

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
