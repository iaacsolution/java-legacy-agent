package com.legacy.banking;

import java.sql.*;
import java.util.logging.Logger;

/**
 * EJB Session Bean — gestion des clients (Java EE 5, 2009).
 * Accès direct JDBC sans couche ORM.
 */
public class ClientServiceBean implements ClientService {

    private static final Logger log = Logger.getLogger(ClientServiceBean.class.getName());
    private DataSource dataSource;

    public Client findClientByCode(String codeClient) throws ServiceException {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = dataSource.getConnection();
            ps = conn.prepareStatement(
                "SELECT CLI_ID, CLI_NOM, CLI_PRENOM, CLI_STATUT, CLI_DATE_CREATION " +
                "FROM T_CLIENT WHERE CLI_CODE = ? AND CLI_STATUT != 'S'"
            );
            ps.setString(1, codeClient);
            rs = ps.executeQuery();
            if (rs.next()) {
                Client client = new Client();
                client.setId(rs.getLong("CLI_ID"));
                client.setNom(rs.getString("CLI_NOM"));
                client.setPrenom(rs.getString("CLI_PRENOM"));
                client.setStatut(rs.getString("CLI_STATUT"));
                return client;
            }
            return null;
        } catch (SQLException e) {
            log.severe("Erreur findClientByCode: " + codeClient + " — " + e.getMessage());
            throw new ServiceException("Erreur base de donnees", e);
        } finally {
            try { if (rs   != null) rs.close();   } catch (SQLException ignored) {}
            try { if (ps   != null) ps.close();   } catch (SQLException ignored) {}
            try { if (conn != null) conn.close();  } catch (SQLException ignored) {}
        }
    }

    public void updateClientStatut(Long clientId, String statut) throws ServiceException {
        if (statut == null || (!statut.equals("A") && !statut.equals("I") && !statut.equals("S"))) {
            throw new ServiceException("Statut invalide: " + statut);
        }
        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);
            ps = conn.prepareStatement(
                "UPDATE T_CLIENT SET CLI_STATUT = ?, CLI_DATE_MAJ = SYSDATE WHERE CLI_ID = ?"
            );
            ps.setString(1, statut);
            ps.setLong(2, clientId);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                conn.rollback();
                throw new ServiceException("Client introuvable: " + clientId);
            }
            conn.commit();
            log.info("Client " + clientId + " statut mis à jour: " + statut);
        } catch (SQLException e) {
            try { if (conn != null) conn.rollback(); } catch (SQLException ignored) {}
            throw new ServiceException("Erreur mise a jour statut", e);
        } finally {
            try { if (ps   != null) ps.close();   } catch (SQLException ignored) {}
            try { if (conn != null) conn.close();  } catch (SQLException ignored) {}
        }
    }
}
