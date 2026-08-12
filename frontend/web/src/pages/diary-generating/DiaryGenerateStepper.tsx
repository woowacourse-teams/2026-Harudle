const DiaryGenerateStepper = ({ loadingStep }: { loadingStep: number }) => {
  return (
    <div>
      <div>이야기 분석 중 {loadingStep === 1 && '여기'}</div>
      <div>장면 구성 중 {loadingStep === 2 && '여기'}</div>
      <div>스케치 그리는 중 {loadingStep === 3 && '여기'}</div>
      <div>채색하고 마무리 중{loadingStep === 4 && '여기'}</div>
    </div>
  );
};

export default DiaryGenerateStepper;
