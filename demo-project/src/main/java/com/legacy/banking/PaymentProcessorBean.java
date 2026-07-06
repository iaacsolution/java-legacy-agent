package com.legacy.banking;

import java.math.BigDecimal;
import java.sql.*;
import java.util.logging.Logger;

/**
 * EJB Session Bean — traitement des paiements (Java EE 5, 2010).
 * Logique métier mélangée avec accès DB. Pas de gestion de devise.
 */
public class PaymentProcessorBean {

    private static final Logger log = Logger.getLogger(PaymentProcessorBean.class.getName());
    private static final String CURRENCY = "EUR";
    private DataSource dataSource;
    private NotificationService notifService;

    public PaymentResult processPayment(Long clientId, BigDecimal amount, String type) throws Exception {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Montant invalide: " + amount);
        }
        if (amount.compareTo(new BigDecimal("50000")) > 0) {
            log.warning("Paiement > 50000€ détecté — client=" + clientId + " montant=" + amount);
        }

        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);

            // Vérification solde disponible
            BigDecimal solde = getSolde(conn, clientId);
            if (solde.compareTo(amount) < 0) {
                conn.rollback();
                return new PaymentResult(false, "Solde insuffisant", null);
            }

            // Débit compte
            PreparedStatement debit = conn.prepareStatement(
                "UPDATE T_COMPTE SET CPT_SOLDE = CPT_SOLDE - ?, CPT_DATE_MAJ = SYSDATE " +
                "WHERE CPT_CLIENT_ID = ? AND CPT_TYPE = 'C'"
            );
            debit.setBigDecimal(1, amount);
            debit.setLong(2, clientId);
            debit.executeUpdate();
            debit.close();

            // Insertion transaction
            PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO T_TRANSACTION (TRX_CLIENT_ID, TRX_MONTANT, TRX_TYPE, TRX_DATE, TRX_STATUT) " +
                "VALUES (?, ?, ?, SYSDATE, 'OK')",
                Statement.RETURN_GENERATED_KEYS
            );
            insert.setLong(1, clientId);
            insert.setBigDecimal(2, amount);
            insert.setString(3, type);
            insert.executeUpdate();

            ResultSet keys = insert.getGeneratedKeys();
            String transactionId = keys.next() ? keys.getString(1) : "UNKNOWN";
            insert.close();

            conn.commit();
            notifService.sendPaymentConfirmation(clientId, amount, transactionId);
            return new PaymentResult(true, "OK", transactionId);

        } catch (SQLException e) {
            try { if (conn != null) conn.rollback(); } catch (SQLException ignored) {}
            log.severe("Erreur paiement client=" + clientId + ": " + e.getMessage());
            throw new ServiceException("Erreur paiement", e);
        } finally {
            try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
        }
    }

    private BigDecimal getSolde(Connection conn, Long clientId) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(
            "SELECT CPT_SOLDE FROM T_COMPTE WHERE CPT_CLIENT_ID = ? AND CPT_TYPE = 'C'"
        );
        ps.setLong(1, clientId);
        ResultSet rs = ps.executeQuery();
        BigDecimal solde = rs.next() ? rs.getBigDecimal("CPT_SOLDE") : BigDecimal.ZERO;
        rs.close();
        ps.close();
        return solde;
    }
}
