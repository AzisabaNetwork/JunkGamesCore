package net.azisaba.jg.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MathUtility
{
    public static boolean isInt(String s)
    {
        Pattern pattern = Pattern.compile("^[0-9]+$");
        Matcher matcher = pattern.matcher(s);
        return matcher.matches();
    }

    public static boolean isDouble(String s)
    {
        try
        {
            Double.valueOf(s);
            return true;
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }
}
