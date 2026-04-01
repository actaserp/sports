package mes.domain.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BankAccsaveRequestDto {

	private String transactionDate;
	private String transactionHour;
	private String inoutFlag; // 0: 입금, 1: 출금
	private  String bnkcode;

	private String clientId;
	private String clientFlag;

	private String bankcode_popup;
	private String bankName;

	private String commission;

	private String accountNumber;
	private String accountId;

	private String money;

	private String transactionTypeId;
	private String depositAndWithdrawalType;

	private String bill;
	private String etc;
	private String expiration;

	private String note1;	//적요
	private String memo;
}