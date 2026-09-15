       01 POLICY-MASTER-RECORD.
          05 PMR-POLICY-NUMBER          PIC X(10).
          05 PMR-POLICY-TYPE            PIC X(02).
             88 PMR-LIFE-INSURANCE          VALUE 'LI'.
             88 PMR-HEALTH-INSURANCE        VALUE 'HI'.
             88 PMR-AUTO-INSURANCE          VALUE 'AI'.
             88 PMR-HOME-INSURANCE          VALUE 'HO'.
             88 PMR-DISABILITY-INS          VALUE 'DI'.
             88 PMR-COMMERCIAL-INS          VALUE 'CI'.
          05 PMR-CUSTOMER-NUMBER        PIC X(08).
          05 PMR-EFFECTIVE-DATE         PIC 9(08).
          05 PMR-EXPIRY-DATE            PIC 9(08).
          05 PMR-PREMIUM-AMOUNT         PIC 9(07)V99 COMP-3.
          05 PMR-COVERAGE-AMOUNT        PIC 9(09)V99 COMP-3.
          05 PMR-POLICY-STATUS          PIC X(01).
             88 PMR-POLICY-ACTIVE           VALUE 'A'.
             88 PMR-POLICY-LAPSED           VALUE 'L'.
             88 PMR-POLICY-CANCELLED        VALUE 'C'.
             88 PMR-POLICY-PENDING          VALUE 'P'.
             88 PMR-POLICY-SUSPENDED        VALUE 'S'.
          05 PMR-DEDUCTIBLE-AMOUNT      PIC 9(07)V99 COMP-3.
          05 PMR-AGENT-CODE             PIC X(06).
          05 PMR-BRANCH-CODE            PIC X(04).
          05 PMR-UNDERWRITER-CODE       PIC X(08).
          05 PMR-LAST-RENEWAL-DATE      PIC 9(08).
          05 PMR-NEXT-RENEWAL-DATE      PIC 9(08).
          05 PMR-BENEFICIARY-COUNT      PIC 9(02) COMP-3.
          05 PMR-PAYMENT-FREQUENCY      PIC X(01).
             88 PMR-MONTHLY                 VALUE 'M'.
             88 PMR-QUARTERLY               VALUE 'Q'.
             88 PMR-SEMI-ANNUAL             VALUE 'S'.
             88 PMR-ANNUAL                  VALUE 'A'.
          05 PMR-RISK-RATING            PIC 9(02) COMP-3.
          05 PMR-DISCOUNT-PERCENTAGE    PIC 9(03)V99 COMP-3.
          05 PMR-OPEN-CLAIMS-COUNT      PIC 9(03) COMP-3.
          05 PMR-TOTAL-CLAIMS-PAID      PIC 9(09)V99 COMP-3.
          05 PMR-LAST-CLAIM-DATE        PIC 9(08).
          05 PMR-CO-INSURED-NAME        PIC X(30).
          05 PMR-STATE-JURISDICTION     PIC X(02).
          05 PMR-POLICY-NOTES           PIC X(50).
          05 PMR-FILLER                 PIC X(05).