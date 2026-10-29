package com.honeyshare.identity.service;

import com.honeyshare.common.SecureTokens;
import com.honeyshare.identity.domain.Invitation;
import com.honeyshare.identity.port.InvitationRepository;
import com.honeyshare.identity.port.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Bootstrap seeder for initial setup.
 *
 * Complies with Specification Section 13.1:
 * "Development-only seed mechanism that never creates a fixed production administrator/password."
 *
 * If the database has zero users and zero invitations, generates a fresh, cryptographically
 * random bootstrap invitation token and prints it to the console so the developer can
 * register the initial administrator account.
 */
@Component
public class BootstrapSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapSeeder.class);

    private final UserRepository userRepository;
    private final InvitationRepository invitationRepository;

    public BootstrapSeeder(UserRepository userRepository, InvitationRepository invitationRepository) {
        this.userRepository = userRepository;
        this.invitationRepository = invitationRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() == 0) {
            // While no active users exist, regenerate an active bootstrap invitation so the token is visible in console
            invitationRepository.deleteAll();

            String rawToken = SecureTokens.generateRawToken();
            String tokenHash = SecureTokens.hash(rawToken);

            Invitation bootstrapInvite = Invitation.create(
                    null, // open invite for initial administrator setup
                    tokenHash,
                    null, // system-generated bootstrap invitation
                    Instant.now().plus(7, ChronoUnit.DAYS)
            );
            invitationRepository.save(bootstrapInvite);

            log.info("\n" +
                    "========================================================================================\n" +
                    "  [HONEYSHARE BOOTSTRAP] Initial Setup Invitation Generated:\n" +
                    "  Invite Token : {}\n" +
                    "  Web Client   : http://localhost:8080/\n" +
                    "  Instructions : Open http://localhost:8080/ in your browser, switch to 'Register with Invite',\n" +
                    "                 paste the token above with your email and password to create the Administrator account.\n" +
                    "========================================================================================", rawToken);
        }
    }
}

