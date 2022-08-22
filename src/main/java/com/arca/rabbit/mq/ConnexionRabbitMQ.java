package com.arca.rabbit.mq;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ConnexionRabbitMQ {

    public static void main(String[] args) {
        RabbitMQReceiver receiver = new RabbitMQReceiver();
        try {
            receiver.start();
        } catch (IOException | TimeoutException ex) {
            Logger.getLogger(ConnexionRabbitMQ.class.getName()).log(Level.SEVERE, null, ex);
        }

    }
}
