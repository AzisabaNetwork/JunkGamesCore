package net.azisaba.jg.util;

import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class SqlUtility
{
    public static boolean test(String url, String user, String pass)
    {
        try
        {
            Connection con = DriverManager.getConnection(url, user, pass);
            PreparedStatement stmt = con.prepareStatement("SHOW DATABASES");

            stmt.executeUpdate();
            stmt.close();
            con.close();
            return true;
        }
        catch (SQLException e)
        {
            System.out.println(e.getMessage());
            return false;
        }
    }

    public static void jdbc(@NotNull String driver)
    {
        try
        {
            Class.forName(driver);
        }
        catch (ClassNotFoundException ignored)
        {

        }
    }
}
