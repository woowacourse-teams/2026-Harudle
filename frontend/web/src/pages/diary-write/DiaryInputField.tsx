const DiaryInputField = ({
  diaryContent,
  onDiaryContentChange,
}: {
  diaryContent: string;
  onDiaryContentChange: (e: React.ChangeEvent<HTMLTextAreaElement>) => void;
}) => {
  return (
    <div>
      <textarea
        placeholder="오늘은 민지와 함께 카페에 갔다. 처음으로 아이스 아메리카노를 마셨는데 너무 썼다. 
      다음에는 복숭아 아이스티를 마셔야겠다!"
        value={diaryContent}
        onChange={onDiaryContentChange}
      />
      <span>에러 메시지</span>
    </div>
  );
};

export default DiaryInputField;
