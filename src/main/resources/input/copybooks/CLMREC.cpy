       01 CLAIMS-RECORD.
          05 CLM-CLAIM-NUMBER           PIC X(12).
          05 CLM-POLICY-NUMBER          PIC X(10).
          05 CLM-CUSTOMER-NUMBER        PIC X(08).
          05 CLM-CLAIM-TYPE             PIC X(02).
             88 CLM-MEDICAL-CLAIM           VALUE 'MC'.
             88 CLM-PROPERTY-CLAIM          VALUE 'PC'.
             88 CLM-LIABILITY-CLAIM         VALUE 'LC'.
             88 CLM-AUTO-COLLISION          VALUE 'AC'.
             88 CLM-AUTO-COMPREHENSIVE      VALUE 'AX'.
             88 CLM-LIFE-CLAIM              VALUE 'LF'.
             88 CLM-DISABILITY-CLAIM        VALUE 'DC'.
          05 CLM-CLAIM-DATE             PIC 9(08).
          05 CLM-INCIDENT-DATE          PIC 9(08).
          05 CLM-REPORTED-DATE          PIC 9(08).
          05 CLM-CLAIM-AMOUNT           PIC 9(09)V99 COMP-3.
          05 CLM-APPROVED-AMOUNT        PIC 9(09)V99 COMP-3.
          05 CLM-PAID-AMOUNT            PIC 9(09)V99 COMP-3.
          05 CLM-DEDUCTIBLE-APPLIED     PIC 9(07)V99 COMP-3.
          05 CLM-CLAIM-STATUS           PIC X(01).
             88 CLM-PENDING                 VALUE 'P'.
             88 CLM-UNDER-REVIEW            VALUE 'U'.
             88 CLM-APPROVED                VALUE 'A'.
             88 CLM-REJECTED                VALUE 'R'.
             88 CLM-PARTIALLY-PAID          VALUE 'X'.
             88 CLM-PAID-IN-FULL            VALUE 'D'.
             88 CLM-CLOSED                  VALUE 'C'.
          05 CLM-ADJUSTER-ID            PIC X(08).
          05 CLM-ADJUSTER-NAME          PIC X(30).
          05 CLM-INCIDENT-LOCATION      PIC X(60).
          05 CLM-INCIDENT-DESCRIPTION   PIC X(80).
          05 CLM-REJECTION-REASON-CODE  PIC X(04).
          05 CLM-REJECTION-REASON-TEXT  PIC X(50).
          05 CLM-PROCESS-DATE           PIC 9(08).
          05 CLM-PAYMENT-DATE           PIC 9(08).
          05 CLM-THIRD-PARTY-FLAG       PIC X(01).
             88 CLM-THIRD-PARTY-INVOLVED    VALUE 'Y'.
             88 CLM-NO-THIRD-PARTY          VALUE 'N'.
          05 CLM-POLICE-REPORT-NUMBER   PIC X(15).
          05 CLM-FILLER                 PIC X(11).