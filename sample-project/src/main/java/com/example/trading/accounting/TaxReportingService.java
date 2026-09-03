package com.example.trading.accounting;

public class TaxReportingService {
    public double computeCapitalGainsTax(double shortTermGain, double longTermGain) {
        double shortTermTax = shortTermGain > 0 ? shortTermGain * 0.35 : 0.0;
        double longTermTax = longTermGain > 0 ? longTermGain * 0.15 : 0.0;
        return shortTermTax + longTermTax;
    }
}
