/**
 * 
 */
package dev.vsuarez.model;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.Properties;

import org.compiere.model.MBPGroup;
import org.compiere.model.MBPartner;
import org.compiere.model.MSysConfig;
import org.compiere.model.X_I_BPartner;
import org.compiere.util.Env;

/**
 * @author <a href="mailto:victor.suarez.is@gmail.com">Ing. Victor Suarez</a>
 *
 */
public class IVS_MBPartner extends MBPartner {

	/**
	 * 
	 */
	private static final long serialVersionUID = 8981856328359020456L;

	/**
	 * @param ctx
	 */
	public IVS_MBPartner(Properties ctx) {
		super(ctx);
		// TODO Auto-generated constructor stub
	}

	/**
	 * @param ctx
	 * @param rs
	 * @param trxName
	 */
	public IVS_MBPartner(Properties ctx, ResultSet rs, String trxName) {
		super(ctx, rs, trxName);
		// TODO Auto-generated constructor stub
	}

	/**
	 * @param ctx
	 * @param C_BPartner_ID
	 * @param trxName
	 */
	public IVS_MBPartner(Properties ctx, int C_BPartner_ID, String trxName) {
		super(ctx, C_BPartner_ID, trxName);
		// TODO Auto-generated constructor stub
	}

	/**
	 * @param impBP
	 */
	public IVS_MBPartner(X_I_BPartner impBP) {
		super(impBP);
		// TODO Auto-generated constructor stub
	}

	/**
	 * @param copy
	 */
	public IVS_MBPartner(MBPartner copy) {
		super(copy);
		// TODO Auto-generated constructor stub
	}

	/**
	 * @param ctx
	 * @param copy
	 */
	public IVS_MBPartner(Properties ctx, MBPartner copy) {
		super(ctx, copy);
		// TODO Auto-generated constructor stub
	}

	/**
	 * @param ctx
	 * @param copy
	 * @param trxName
	 */
	public IVS_MBPartner(Properties ctx, MBPartner copy, String trxName) {
		super(ctx, copy, trxName);
		// TODO Auto-generated constructor stub
	}
	
	/**
	 * 	Get SO CreditStatus with additional amount
	 * 	@param additionalAmt additional amount in Accounting Currency
	 *	@return simulated credit status
	 */
	@Override
	public String getSOCreditStatus (BigDecimal additionalAmt) {
		if (additionalAmt == null || additionalAmt.signum() == 0)
			return getSOCreditStatus();
		//
		BigDecimal creditLimit = getSO_CreditLimit(); 
		//	Nothing to do
		if (SOCREDITSTATUS_NoCreditCheck.equals(getSOCreditStatus())
			|| SOCREDITSTATUS_CreditStop.equals(getSOCreditStatus())
			|| Env.ZERO.compareTo(creditLimit) == 0)
			return getSOCreditStatus();

		// Add % Tolerance on Credit Limit
		BigDecimal tolerance = BigDecimal.ZERO;
		if(get_Value("SO_ToleranceOnCreditLimit") != null)
			tolerance = (BigDecimal) get_Value("SO_ToleranceOnCreditLimit");
		if(tolerance.signum() <= 0) {
			MBPGroup bpg = (MBPGroup) getC_BP_Group();
			if(bpg.get_Value("SO_ToleranceOnCreditLimit") != null)
				tolerance = (BigDecimal) bpg.get_Value("SO_ToleranceOnCreditLimit");
		}
		if(tolerance.signum() <= 0)
			tolerance = MSysConfig.getBigDecimalValue("TOLERANCE_ON_THE_CREDIT_LIMIT", BigDecimal.ZERO, getAD_Client_ID());
		if(tolerance.signum() > 0)
			creditLimit = creditLimit.multiply((tolerance.divide(new BigDecimal(100))).add(BigDecimal.ONE));
		//	Above (reduced) Credit Limit
		creditLimit = creditLimit.subtract(additionalAmt);
		if (creditLimit.compareTo(getTotalOpenBalance()) < 0)
			return SOCREDITSTATUS_CreditHold;
		
		//	Above Watch Limit
		BigDecimal watchAmt = creditLimit.multiply(getCreditWatchRatio());
		if (watchAmt.compareTo(getTotalOpenBalance()) < 0)
			return SOCREDITSTATUS_CreditWatch;
		
		//	is OK
		return SOCREDITSTATUS_CreditOK;
	}	//	getSOCreditStatus

}
