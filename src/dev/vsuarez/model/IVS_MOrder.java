/**
 * 
 */
package dev.vsuarez.model;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.Properties;
import java.util.logging.Level;

import org.compiere.model.MBPartner;
import org.compiere.model.MConversionRate;
import org.compiere.model.MDocType;
import org.compiere.model.MOrder;
import org.compiere.model.MOrderLine;
import org.compiere.model.MOrderPaySchedule;
import org.compiere.model.MPeriod;
import org.compiere.model.MProduct;
import org.compiere.model.MProject;
import org.compiere.model.MSysConfig;
import org.compiere.model.ModelValidationEngine;
import org.compiere.model.ModelValidator;
import org.compiere.process.DocAction;
import org.compiere.util.CLogger;
import org.compiere.util.Msg;
import org.compiere.util.Util;

/**
 * @author <a href="mailto:victor.suarez.is@gmail.com">Ing. Victor Suarez</a>
 *
 */
public class IVS_MOrder extends MOrder implements DocAction {

	/**
	 * 
	 */
	private static final long serialVersionUID = 7540745033424956197L;

	/**
	 * @param ctx
	 * @param C_Order_ID
	 * @param trxName
	 */
	public IVS_MOrder(Properties ctx, int C_Order_ID, String trxName) {
		super(ctx, C_Order_ID, trxName);
		// TODO Auto-generated constructor stub
	}

	/**
	 * @param project
	 * @param IsSOTrx
	 * @param DocSubTypeSO
	 */
	public IVS_MOrder(MProject project, boolean IsSOTrx, String DocSubTypeSO) {
		super(project, IsSOTrx, DocSubTypeSO);
		// TODO Auto-generated constructor stub
	}

	/**
	 * @param ctx
	 * @param rs
	 * @param trxName
	 */
	public IVS_MOrder(Properties ctx, ResultSet rs, String trxName) {
		super(ctx, rs, trxName);
		// TODO Auto-generated constructor stub
	}
	
	/**************************************************************************
	 *	Prepare Document
	 * 	@return new status (In Progress or Invalid) 
	 */
	@Override
	public String prepareIt() {
		if (log.isLoggable(Level.INFO)) log.info(toString());
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_BEFORE_PREPARE);
		if (m_processMsg != null)
			return DocAction.STATUS_Invalid;
		MDocType dt = MDocType.get(getCtx(), getC_DocTypeTarget_ID());

		//	Std Period open?
		if (!MPeriod.isOpen(getCtx(), getDateAcct(), dt.getDocBaseType(), getAD_Org_ID()))
		{
			m_processMsg = "@PeriodClosed@";
			return DocAction.STATUS_Invalid;
		}
		
		if (isSOTrx() && getDeliveryViaRule().equals(DELIVERYVIARULE_Shipper))
		{
			if (getM_Shipper_ID() == 0)
			{
				m_processMsg = "@FillMandatory@" + Msg.getElement(getCtx(), COLUMNNAME_M_Shipper_ID);
				return DocAction.STATUS_Invalid;
			}
			
			if (!calculateFreightCharge())
				return DocAction.STATUS_Invalid;
		}

		//	Lines
		MOrderLine[] lines = getLines(true, MOrderLine.COLUMNNAME_M_Product_ID);
		if (lines.length == 0)
		{
			m_processMsg = "@NoLines@";
			return DocAction.STATUS_Invalid;
		}
				
		// Bug 1564431
		if (MOrder.DELIVERYRULE_CompleteOrder.equals(getDeliveryRule()) )
		{
			for (int i = 0; i < lines.length; i++) 
			{
				MOrderLine line = lines[i];
				MProduct product = line.getProduct();
				if (product != null && product.isExcludeAutoDelivery())
				{
					m_processMsg = "@M_Product_ID@ "+product.getValue()+" @IsExcludeAutoDelivery@";
					return DocAction.STATUS_Invalid;
				}
				if (line.getDatePromised() != null && !line.getDatePromised().equals(getDatePromised()))
				{
					m_processMsg = "@Line@ " + line.getLine() + " - @Invalid@ @DatePromised@";
					return DocAction.STATUS_Invalid;
				}
			}
		}
		
		//	Convert DocType to Target
		if (getC_DocType_ID() != getC_DocTypeTarget_ID() )
		{
			//	Cannot change Std to anything else if different warehouses
			if (getC_DocType_ID() != 0)
			{
				MDocType dtOld = MDocType.get(getCtx(), getC_DocType_ID());
				if (MDocType.DOCSUBTYPESO_StandardOrder.equals(dtOld.getDocSubTypeSO())		//	From SO
					&& !MDocType.DOCSUBTYPESO_StandardOrder.equals(dt.getDocSubTypeSO()))	//	To !SO
				{
					for (int i = 0; i < lines.length; i++)
					{
						if (lines[i].getM_Warehouse_ID() != getM_Warehouse_ID())
						{
							log.warning("different Warehouse " + lines[i]);
							m_processMsg = "@CannotChangeDocType@";
							return DocAction.STATUS_Invalid;
						}
					}
				}
			}
			
			//	New or in Progress/Invalid
			if (DOCSTATUS_Drafted.equals(getDocStatus()) 
				|| DOCSTATUS_InProgress.equals(getDocStatus())
				|| DOCSTATUS_Invalid.equals(getDocStatus())
				|| getC_DocType_ID() == 0)
			{
				setC_DocType_ID(getC_DocTypeTarget_ID());
			}
			else	//	convert only if offer
			{
				if (dt.isOffer())
					setC_DocType_ID(getC_DocTypeTarget_ID());
				else
				{
					m_processMsg = "@CannotChangeDocType@";
					return DocAction.STATUS_Invalid;
				}
			}
		}	//	convert DocType

		//	Mandatory Product Attribute Set Instance
		for (MOrderLine line : getLines()) {
			if (line.getM_Product_ID() > 0 && line.getM_AttributeSetInstance_ID() == 0) {
				MProduct product = line.getProduct();
				if (product.isASIMandatory(isSOTrx())) {
					if (product.getAttributeSet() != null && !product.getAttributeSet().excludeTableEntry(MOrderLine.Table_ID, isSOTrx())) {
						StringBuilder msg = new StringBuilder("@M_AttributeSet_ID@ @IsMandatory@ (@Line@ #")
							.append(line.getLine())
							.append(", @M_Product_ID@=")
							.append(product.getValue())
							.append(")");
						m_processMsg = msg.toString();
						return DocAction.STATUS_Invalid;
					}
				}
			}
		}

		//	Lines
		if (explodeBOM())
			lines = getLines(true, MOrderLine.COLUMNNAME_M_Product_ID);
		if (!reserveStock(dt, lines))
		{
			String innerMsg = CLogger.retrieveErrorString("");
			m_processMsg = "Cannot reserve Stock";
			if (! Util.isEmpty(innerMsg))
				m_processMsg = m_processMsg + " -> " + innerMsg;
			return DocAction.STATUS_Invalid;
		}
		if (!calculateTaxTotal())
		{
			m_processMsg = "Error calculating tax";
			return DocAction.STATUS_Invalid;
		}
		
		if (   getGrandTotal().signum() != 0
			&& (PAYMENTRULE_OnCredit.equals(getPaymentRule()) || PAYMENTRULE_DirectDebit.equals(getPaymentRule())))
		{
			if (!createPaySchedule())
			{
				m_processMsg = "@ErrorPaymentSchedule@";
				return DocAction.STATUS_Invalid;
			}
		} else {
			if (MOrderPaySchedule.getOrderPaySchedule(getCtx(), getC_Order_ID(), 0, get_TrxName()).length > 0) 
			{
				m_processMsg = "@ErrorPaymentSchedule@";
				return DocAction.STATUS_Invalid;
			}
		}
		
		//	Credit Check
		if (isSOTrx())
		{
			if (   MDocType.DOCSUBTYPESO_POSOrder.equals(dt.getDocSubTypeSO())
					&& PAYMENTRULE_Cash.equals(getPaymentRule())
					&& !MSysConfig.getBooleanValue(MSysConfig.CHECK_CREDIT_ON_CASH_POS_ORDER, true, getAD_Client_ID(), getAD_Org_ID())) {
				// ignore -- don't validate for Cash POS Orders depending on sysconfig parameter
			} else if (MDocType.DOCSUBTYPESO_PrepayOrder.equals(dt.getDocSubTypeSO())
					&& !MSysConfig.getBooleanValue(MSysConfig.CHECK_CREDIT_ON_PREPAY_ORDER, true, getAD_Client_ID(), getAD_Org_ID())) {
				// ignore -- don't validate Prepay Orders depending on sysconfig parameter
			} else {
				IVS_MBPartner bp = new IVS_MBPartner (getCtx(), getBill_BPartner_ID(), get_TrxName()); // bill bp is guaranteed on beforeSave

				if (getGrandTotal().signum() > 0)  // IDEMPIERE-365 - just check credit if is going to increase the debt
				{		 

					if (MBPartner.SOCREDITSTATUS_CreditStop.equals(bp.getSOCreditStatus()))
					{
						m_processMsg = "@BPartnerCreditStop@ - @TotalOpenBalance@=" 
								+ bp.getTotalOpenBalance()
								+ ", @SO_CreditLimit@=" + bp.getSO_CreditLimit();
						return DocAction.STATUS_Invalid;
					}
					if (MBPartner.SOCREDITSTATUS_CreditHold.equals(bp.getSOCreditStatus()))
					{
						m_processMsg = "@BPartnerCreditHold@ - @TotalOpenBalance@=" 
								+ bp.getTotalOpenBalance() 
								+ ", @SO_CreditLimit@=" + bp.getSO_CreditLimit();
						return DocAction.STATUS_Invalid;
					}
					BigDecimal grandTotal = MConversionRate.convertBase(getCtx(), 
							getGrandTotal(), getC_Currency_ID(), getDateOrdered(), 
							getC_ConversionType_ID(), getAD_Client_ID(), getAD_Org_ID());
					if (MBPartner.SOCREDITSTATUS_CreditHold.equals(bp.getSOCreditStatus(grandTotal)))
					{
						m_processMsg = "@BPartnerOverOCreditHold@ - @TotalOpenBalance@=" 
								+ bp.getTotalOpenBalance() + ", @GrandTotal@=" + grandTotal
								+ ", @SO_CreditLimit@=" + bp.getSO_CreditLimit();
						return DocAction.STATUS_Invalid;
					}
				}
			}  
		}
		
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_AFTER_PREPARE);
		if (m_processMsg != null)
			return DocAction.STATUS_Invalid;
		
		m_justPrepared = true;
	//	if (!DOCACTION_Complete.equals(getDocAction()))		don't set for just prepare 
	//		setDocAction(DOCACTION_Complete);
		return DocAction.STATUS_InProgress;
	}	//	prepareIt

}
