package br.dev.duofinance.shared.email;

public interface EmailSender {

    void send(EmailMessage message);
}
