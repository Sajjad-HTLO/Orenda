package com.aitp.orenda.auth;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EmailService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final boolean mock;
    private final String from;
    private final String baseUrl;
    private final String verificationSubject;

    public EmailService(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${app.mail.mock:true}") boolean mock,
            @Value("${app.mail.from:no-reply@orenda.app}") String from,
            @Value("${app.base-url:http://localhost:8080}") String baseUrl,
            @Value("${app.mail.verification-subject:Verify your account}") String verificationSubject) {
        this.mailSenderProvider = mailSenderProvider;
        this.mock = mock;
        this.from = from;
        this.baseUrl = baseUrl;
        this.verificationSubject = verificationSubject;
    }

    /**
     * Sends the email-verification link. In mock mode (dev default) the email is
     * only logged so the app works without SMTP credentials.
     */
    public void sendVerificationEmail(String to, String token) {
        String link = baseUrl + "/api/auth/verify-email?token=" + token;
        String body = """
                <html><body>
                <h2>Welcome to Orenda</h2>
                <p>Confirm your email address by clicking the link below:</p>
                <p><a href="%s">Verify my email</a></p>
                <p>This link expires in 24 hours. If you did not create an account,
                you can safely ignore this email.</p>
                </body></html>
                """.formatted(link);

        send(to, verificationSubject, body);
    }

    private void send(String to, String subject, String htmlBody) {
        if (mock) {
            log.info("[MAIL-MOCK] To={} | Subject={} | Body={}", to, subject, htmlBody);
            return;
        }

        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.warn("No JavaMailSender configured and app.mail.mock=false; " +
                    "verification email for {} NOT delivered.", to);
            return;
        }

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            sender.send(message);
            log.info("Verification email sent to {}", to);
        } catch (Exception e) {
            log.error("Failed to send verification email to {}", to, e);
        }
    }
}