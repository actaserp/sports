const CardInputHandler = {
 bind: function ({
                  cardnumSelector = '#CARDNUM',
                  cardnmSelector = '#CARDNM',
                  cardidSelector = '#CARDID',
                  minLength = 2,
                  onComplete = null
                 }) {
  const $cardnum = $(cardnumSelector);
  const $cardnm = $(cardnmSelector);
  const $cardid = $(cardidSelector);

  function setCardInfo(item) {
   $cardnum.val(item.cardnum);
   $cardid.val(item.cardid);
   if ($cardnm.length > 0) {
    $cardnm.val(item.cardnm);
   }

   if (typeof onComplete === 'function') {
    onComplete(item);
   }
  }

  $cardnum.on('input', function () {
   if ($(this).val().trim() === '') {
    if ($cardnm.length > 0) $cardnm.val('');
    $cardid.val('');
   }
  });

  $cardnum.on('keydown', function (e) {
   if (e.key === 'Enter') {
    e.preventDefault();
    const cardNumber = $cardnum.val().replace(/[\s-]/g, '');

    // ✅ 입력이 없으면 무조건 팝업 열기
    if (cardNumber.length === 0) {
     if (typeof PopCardComponent !== 'undefined') {
      const poppage = new PopCardComponent();
      poppage.show(function (item) {
       setCardInfo(item);
      }, cardNumber);
     } else {
      console.error('PopCardComponent가 로드되지 않았습니다.');
     }
     return;
    }

    // ✅ 최소 길이 체크
    if (cardNumber.length < minLength) {
     if (typeof onComplete === 'function') {
      onComplete(null);
     }
     return;
    }

    $.ajax({
     url: '/api/popup/search_Card',
     method: 'GET',
     data :{ CardNumber: cardNumber },
    success: function (res) {
     const items = res;

     if (!items || items.length === 0) {
      Alert.alert('', '해당 카드가 존재하지 않습니다.');
     } else if (items.length === 1) {
      setCardInfo(items[0]);
     } else {
      if (typeof PopCardComponent !== 'undefined') {
       const poppage = new PopCardComponent();
       poppage.show(function (item) {
        setCardInfo(item);
       }, cardNumber);
      } else {
       console.error('PopCardComponent가 로드되지 않았습니다.');
      }
     }
    },
    error: function () {
     Alert.alert('에러', '카드 조회 중 오류가 발생했습니다.');
     $cardnum.focus();
    }
   });
   }
  });
 }
};